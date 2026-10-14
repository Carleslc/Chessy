# Chessy on Lichess

Chessy plays on [Lichess](https://lichess.org) as a BOT through [lichess-bot](https://github.com/lichess-bot-devs/lichess-bot), which connects any UCI engine to the [Lichess Bot API](https://lichess.org/api#tag/Bot).

Chess.com has no API for bots, so Chessy cannot play there (games can still be analyzed there from the exported PGN).

## Setup

1. Build the engine: `./gradlew installDist` in `engine/`. The UCI executable is `engine/build/install/chessy/bin/chessy` (it needs Java 17+).
2. Create a **new** Lichess account for the bot. Upgrading an account to BOT is permanent and only works on accounts that have not played games.
3. Create an [API access token](https://lichess.org/account/oauth/token/create) with the `bot:play` scope.
4. Install lichess-bot following its [instructions](https://github.com/lichess-bot-devs/lichess-bot/wiki), next to this repository:
   ```
   git clone https://github.com/lichess-bot-devs/lichess-bot.git
   cd lichess-bot
   python -m venv venv && source venv/bin/activate
   pip install -r requirements.txt
   ```
5. Copy `config.yml` of this folder into the lichess-bot folder, set the `token` and check that `engine.dir` points to the engine.
6. Upgrade the account to BOT and start the bot:
   ```
   python lichess-bot.py -u
   python lichess-bot.py
   ```

Never commit the token. The bot accepts challenges according to the `challenge` section of the configuration.

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
