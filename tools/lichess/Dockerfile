# syntax=docker/dockerfile:1
# Snaky as a Lichess BOT: the engine and lichess-bot in one image (see README.md in this folder).
# Build it from the root of the repository (compose.yaml does it):
#
#   docker build -f tools/lichess/Dockerfile -t snaky-bot .

# The engine, built with Gradle
FROM eclipse-temurin:17-jdk AS engine
WORKDIR /snaky
COPY engine/ engine/
COPY LICENSE NOTICE ./
RUN cd engine && ./gradlew --no-daemon --quiet installDist

# lichess-bot, at a version tested with Snaky (its versions are dates, without git tags)
FROM python:3.12-slim AS lichess-bot
ARG LICHESS_BOT_COMMIT=f2b38b4f3ec4fc990001c05a4000fcf8adbb339d
RUN apt-get update && apt-get install --yes --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*
RUN git clone --quiet https://github.com/lichess-bot-devs/lichess-bot.git /lichess-bot \
    && git -C /lichess-bot checkout --quiet "$LICHESS_BOT_COMMIT" \
    && rm -rf /lichess-bot/.git

# The bot: Python for lichess-bot, the Java runtime and the engine
FROM python:3.12-slim
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    LICHESS_BOT_DOCKER=true \
    SNAKY_OPTS="-XX:MaxRAMPercentage=50"
COPY --from=eclipse-temurin:17-jre /opt/java/openjdk /opt/java/openjdk
COPY --from=lichess-bot /lichess-bot /lichess-bot
# pytest only for the offline test (entrypoint.sh test)
RUN pip install --no-cache-dir --requirement /lichess-bot/requirements.txt "pytest~=9.1"
COPY --from=engine /snaky/engine/build/install/snaky /snaky
COPY tools/lichess/config.yml /snaky-config/config.yml
COPY tools/lichess/test_offline.py tools/lichess/challenge.py tools/lichess/entrypoint.sh /snaky-tools/
# lichess-bot with the chat commands of Snaky (!explain)
COPY tools/lichess/snaky_bot.py /lichess-bot/snaky_bot.py
RUN chmod +x /snaky-tools/entrypoint.sh \
    && useradd --create-home --shell /usr/sbin/nologin bot \
    && chown -R bot /lichess-bot
USER bot
WORKDIR /lichess-bot

# Optional: Syzygy tablebases in /syzygy (a read-only volume)
VOLUME /syzygy

ENTRYPOINT ["/snaky-tools/entrypoint.sh"]
CMD ["play"]
