from typing import Iterable, Optional, Union

import chess
import chess.svg

def board_svg(board: chess.Board, move: chess.Move = None, coordinates: bool = False, check: Optional[chess.Square] = None, squares: Optional[chess.IntoSquareSet] = None, arrows: Iterable[Union[chess.svg.Arrow, tuple[chess.Square, chess.Square]]] = [], orientation: chess.Color = chess.WHITE, **kwargs):
  return chess.svg.board(
      board=board,
      lastmove=move,
      check=check,
      coordinates=coordinates,
      orientation=orientation,
      squares=squares,
      arrows=arrows,
      **kwargs,
    ).encode('UTF-8')
