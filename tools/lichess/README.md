# Chessy on Lichess

Chessy plays on [Lichess](https://lichess.org) as a BOT through [lichess-bot](https://github.com/lichess-bot-devs/lichess-bot),
which connects any UCI engine to the [Lichess Bot API](https://lichess.org/api#tag/Bot). Bots only play challenges
(from humans or other bots), not tournaments or the lobby pools.

Chess.com has no API for bots, so Chessy cannot play there (games can still be analyzed there from the exported PGN).

## Setup

1. Create a **new** Lichess account for the bot (for example *ChessyBot*). Upgrading an account to BOT is permanent
   and only works on accounts that have not played any game.
2. Logged in with that account, create an [API access token](https://lichess.org/account/oauth/token/create)
   with the `bot:play` scope. Keep it secret: anyone with it can play with the account.
3. Install lichess-bot (Python 3.10+) next to this repository, so that `lichess-bot` and `Chessy` share the parent folder:
   ```
   git clone https://github.com/lichess-bot-devs/lichess-bot.git
   cd lichess-bot
   python3 -m venv venv
   . venv/bin/activate
   pip install -r requirements.txt
   ```
   (or set `LICHESS_BOT_DIR` to its folder).
4. Check that everything works offline, before going online: a game of Chessy through lichess-bot against a mocked
   lichess.org (the one of the tests of lichess-bot), with Stockfish limited to 2000 as the opponent. It needs no token,
   and checks the configuration, the UCI communication, pondering and the clock:
   ```
   pip install -r test_bot/test-requirements.txt   # in the lichess-bot folder, with its venv active
   tools/lichess/run.sh --test
   ```
5. Upgrade the account to BOT and start the bot (it needs Java 17+ to run Chessy):
   ```
   LICHESS_BOT_TOKEN=xxx tools/lichess/run.sh --upgrade
   ```
   The next times, without `--upgrade`:
   ```
   LICHESS_BOT_TOKEN=xxx tools/lichess/run.sh
   ```

`run.sh` builds the engine, copies [`config.yml`](config.yml) into the lichess-bot folder with the path of the engine and
starts lichess-bot. The token is only read from the `LICHESS_BOT_TOKEN` environment variable, never written in a file.
Stop the bot with Ctrl+C.

## Configuration

[`config.yml`](config.yml) sets:

- **Engine**: style Space (UCI option `Style`), 1 thread, 128 MB of hash and pondering (Chessy thinks on the opponent's time).
- **Challenges**: one game at a time, standard chess, from bullet to classical (1 to 60 minutes, increment up to 30 s),
  casual and rated, from humans and bots.
- **Draws and resignation**: Chessy resigns after 5 moves below −10 pawns and offers or accepts draws
  in dead equal endgames.
- **Time**: lichess-bot keeps 1 s for network delays (`move_overhead`) and Chessy 100 ms (`Move Overhead`):
  increase them if the bot loses on time.
- No opening book, no online moves and no tablebases: Chessy plays every move.

To run another style (Full, Material or Greedy), change `Style` in `config.yml`; each style needs its own BOT account
to run at the same time.

## Before going online

Test the engine locally with `tools/match.py`, which uses the same UCI protocol:

```
python match.py --games 100 --tc 60+1
```

## Playing with an electronic board

Once the bot is online, challenge it from your own (human) Lichess account. Any electronic board connected to Lichess
can then play against Chessy:

- **ChessUp 2**: connect the board to your Lichess account in the ChessUp app and challenge the bot.
- **[chessconnect](https://chessconnect.de/)** (Chrome extension, Android and iOS app) connects many boards, ChessUp included,
  to Lichess through its official API. It allows rapid, classical and correspondence games, and also blitz
  in direct challenges, such as a challenge to the bot.

chessconnect and the board apps only connect to online sites, not to local engines, so the Lichess bot is the way
to play Chessy on the board. A bot plays a single configuration: the default style, Space (set `Style` in `config.yml`
to run another one, for example a second bot account with `Full`).
