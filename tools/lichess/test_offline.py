"""
A game of Chessy through lichess-bot against a mocked lichess.org (the one of the tests of lichess-bot), without connecting
to Lichess: the opponent (white) is Stockfish limited to 2000 Elo, with a clock of 60+2.
Checks the configuration, the UCI communication, pondering and the time. Run it with run.sh --test.
"""
import datetime
import logging
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

def simulator(move_queue, board_queue, clock_queue, results):
    # Same clock as the mocked game stream of lichess-bot (60+2)
    wtime, btime, increment = seconds(60), seconds(60), seconds(2)
    board = chess.Board()
    with chess.engine.SimpleEngine.popen_uci("stockfish") as stockfish:
        stockfish.configure({"UCI_LimitStrength": True, "UCI_Elo": 2000})
        while not board.is_game_over() and board.ply() < 200:
            timer = Timer()
            if board.turn == chess.WHITE:
                board.push(stockfish.play(board, chess.engine.Limit(time=0.1)).move)
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
                assert btime > datetime.timedelta(0), "Chessy lost on time"
    board_queue.put(board)
    clock_queue.put((wtime, btime, increment))
    logger.info(f"Game over: {board.result(claim_draw=True)} in {board.ply()} plies, Chessy clock {btime}")
    logger.info(chess.Board().variation_san(board.move_stack))
    results.put(board.is_game_over())

def test_chessy() -> None:
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
    thread = threading.Thread(target=simulator, args=[move_queue, board_queue, clock_queue, results])
    thread.start()
    lichess_bot.start(li, li.get_profile(), CONFIG, logging_level, None, True, one_game=True)
    assert results.get()
    thread.join()
