"""
A game of Snaky through lichess-bot against a mocked lichess.org (the one of the tests of lichess-bot), without connecting
to Lichess: the opponent (white) is Stockfish limited to 2000 Elo if it is installed (or the engine of the TEST_OPPONENT
environment variable), else Snaky itself with the greedy search, with a clock of 60+2.
Checks the configuration, the UCI communication, pondering and the time. Run it with run.sh --test (or the test command
of the Docker image).
"""
import datetime
import logging
import os
import shutil
import threading
import yaml
import chess
import chess.engine
from multiprocessing import Manager
from queue import Queue
import test_bot.lichess
from lib import config, lichess_bot
from lib.timer import Timer, seconds

logging_level = logging.INFO
lichess_bot.logging_configurer(logging_level, None, True)
logger = logging.getLogger(__name__)

def opponent(snaky: str) -> tuple[str, dict]:
    """Command and options of the opponent: TEST_OPPONENT, Stockfish limited to 2000 Elo, or Snaky with the greedy search."""
    if os.environ.get("TEST_OPPONENT"):
        return os.environ["TEST_OPPONENT"], {}
    if shutil.which("stockfish"):
        return "stockfish", {"UCI_LimitStrength": True, "UCI_Elo": 2000}
    return snaky, {"Style": "Greedy"}

def simulator(move_queue, board_queue, clock_queue, results, opponent_command, opponent_options):
    # Same clock as the mocked game stream of lichess-bot (60+2)
    wtime, btime, increment = seconds(60), seconds(60), seconds(2)
    board = chess.Board()
    with chess.engine.SimpleEngine.popen_uci(opponent_command) as engine:
        engine.configure(opponent_options)
        logger.info(f"Opponent: {engine.id.get('name')} {opponent_options}")
        while not board.is_game_over() and board.ply() < 200:
            timer = Timer()
            if board.turn == chess.WHITE:
                board.push(engine.play(board, chess.engine.Limit(time=0.1)).move)
                board_queue.put(board)
                clock_queue.put((wtime, btime, increment))
            else:
                while (bot_move := move_queue.get()) is None:
                    board_queue.put(board)
                    clock_queue.put((wtime, btime, increment))
                    move_queue.task_done()
                board.push(bot_move)
                move_queue.task_done()
                if board.ply() > 2:
                    btime -= timer.time_since_reset()
                    btime += increment
                assert btime > datetime.timedelta(0), "Snaky lost on time"
    board_queue.put(board)
    clock_queue.put((wtime, btime, increment))
    logger.info(f"Game over: {board.result(claim_draw=True)} in {board.ply()} plies, Snaky clock {btime}")
    logger.info(chess.Board().variation_san(board.move_stack))
    results.put(board.is_game_over())

def test_snaky() -> None:
    with open("config.yml") as file:
        raw = yaml.safe_load(file)
    raw["token"] = ""
    raw["engine"]["draw_or_resign"]["resign_enabled"] = False  # The simulator does not handle resignations
    raw["pgn_directory"] = None
    config.insert_default_values(raw)
    CONFIG = config.Configuration(raw)
    manager = Manager()
    board_queue, clock_queue, move_queue, results = manager.Queue(), manager.Queue(), manager.Queue(), manager.Queue()
    li = test_bot.lichess.Lichess(move_queue, board_queue, clock_queue)
    lichess_bot.disable_restart()
    command, options = opponent(os.path.join(raw["engine"]["dir"], raw["engine"]["name"]))
    thread = threading.Thread(target=simulator, args=[move_queue, board_queue, clock_queue, results, command, options])
    thread.start()
    lichess_bot.start(li, li.get_profile(), CONFIG, logging_level, None, True, one_game=True)
    assert results.get()
    thread.join()
