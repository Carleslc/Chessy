"""
Starting point of lichess-bot for Snaky: lichess-bot with the chat commands of Snaky. It replaces lichess-bot.py
(copy it into the lichess-bot folder, as run.sh and the Docker image do):

  python snaky_bot.py --config config.yml

Commands (!help): !name, !eval and !queue of lichess-bot, without !wait (it delays the abort of the game for a
minute before the first move), and !explain [move]: the static evaluation of Snaky term by term (snaky eval, without
searching, unlike !eval), for the current position or after a move (!explain 23 after the move 23 of white,
!explain 23b after the move 23 of black). Like !eval, only for the spectators: the opponent does not get it.
"""
import os
import subprocess
import sys
import threading
import time

import chess
import yaml

from lib import lichess_bot
from lib.conversation import ChatLine, Conversation
from lib.lichess import MAX_CHAT_MESSAGE_LEN

# Seconds between two explanations of the same game, so that the chat is not flooded
EXPLAIN_INTERVAL = 5


def engine_settings() -> tuple[list[str], dict[str, str]]:
    """Command of the engine and its options (Style and Weights), from the configuration of lichess-bot."""
    config_path = sys.argv[sys.argv.index("--config") + 1] if "--config" in sys.argv else "config.yml"
    with open(config_path) as file:
        engine = yaml.safe_load(file)["engine"]
    command = os.path.join(engine["dir"], engine["name"])
    options = engine.get("uci_options") or {}
    return [command], {name: str(options[name]) for name in ("Style", "Weights") if options.get(name)}


def explained_board(game, argument: str) -> chess.Board:
    """The position of the game after the move given as "23" (white) or "23b" (black), or the current position."""
    chess960 = game.variant_name == "Chess960"
    fen = game.initial_fen if game.initial_fen and game.initial_fen != "startpos" else chess.STARTING_FEN
    board = chess.Board(fen, chess960=chess960)
    moves = game.state["moves"].split()
    plies = len(moves)
    if argument:
        black = argument.endswith("b")
        number = argument.rstrip("bw")
        if not number.isdigit() or int(number) < 1:
            raise ValueError("Usage: !explain, !explain 23 (after the move 23 of white) or !explain 23b (of black)")
        plies = (int(number) - board.fullmove_number) * 2 + (2 if black else 1) - (1 if board.turn == chess.BLACK else 0)
        if not 0 < plies <= len(moves):
            raise ValueError(f"Move {argument} has not been played yet")
    for move in moves[:plies]:
        board.push_uci(move)
    return board


def chat_lines(text: str) -> list[str]:
    """The text split into chat messages (at most 3), at the commas."""
    messages = [""]
    for part in text.split(", "):
        candidate = f"{messages[-1]}, {part}" if messages[-1] else part
        if len(candidate) <= MAX_CHAT_MESSAGE_LEN:
            messages[-1] = candidate
        else:
            messages.append(part[:MAX_CHAT_MESSAGE_LEN])
    return messages[:3]


class SnakyConversation(Conversation):
    """The conversation of lichess-bot with the commands of Snaky."""

    last_explain = 0.0

    def command(self, line: ChatLine, cmd: str) -> None:
        """React to the commands of the chat: !explain and the commands of lichess-bot but !wait."""
        name, _, argument = cmd.partition(" ")
        if cmd in ("commands", "help"):
            self.send_reply(line, "Supported commands: !name, !eval, !explain [move, e.g. 23 or 23b for black], !queue")
        elif cmd == "wait":
            return
        elif name == "explain":
            self.explain(line, argument.strip())
        else:
            super().command(line, cmd)

    def explain(self, line: ChatLine, argument: str) -> None:
        """The evaluation term by term, computed in the background so that the game is not delayed."""
        if line.username != self.game.username and line.room != "spectator":
            self.send_reply(line, "I don't tell that to my opponent, sorry.")
            return
        if time.monotonic() - self.last_explain < EXPLAIN_INTERVAL:
            return
        self.last_explain = time.monotonic()
        try:
            board = explained_board(self.game, argument)
        except ValueError as error:
            self.send_reply(line, str(error))
            return
        threading.Thread(target=self.send_explanation, args=(line, board, argument), daemon=True).start()

    def send_explanation(self, line: ChatLine, board: chess.Board, argument: str) -> None:
        command, options = engine_settings()
        arguments = [*command, "eval", board.fen(), "--format", "line"]
        if "Style" in options:
            arguments += ["--style", options["Style"]]
        if "Weights" in options:
            arguments += ["--weights", options["Weights"]]
        if board.chess960:
            arguments += ["--chess960", "true"]
        try:
            result = subprocess.run(arguments, capture_output=True, text=True, timeout=20, check=True)
            text = result.stdout.strip().splitlines()[-1]
        except (OSError, subprocess.SubprocessError, IndexError):
            self.send_reply(line, "The evaluation failed, sorry.")
            return
        # "after 23.Nf5" or "after 23...Nc6": the last move of the explained position
        position = "Now"
        if board.move_stack:
            last = board.pop()
            dots = "." if board.turn == chess.WHITE else "..."
            position = f"After {board.fullmove_number}{dots}{board.san(last)}"
            board.push(last)
        # "Full -1.04: Mobility -0.68, ..." as "After 4...Nf6 (static Full) -1.04: Mobility -0.68, ...": without search,
        # unlike !eval
        style, _, terms = text.partition(" ")
        for message in chat_lines(f"{position} (static {style}) {terms}"):
            self.send_reply(line, message)


# Replaced at import, also in the processes of the games (multiprocessing imports this module again)
lichess_bot.Conversation = SnakyConversation

if __name__ == "__main__":
    lichess_bot.start_program()
