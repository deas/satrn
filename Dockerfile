# Most other images are missing git, curl or whatnot
FROM openjdk:11 as build
LABEL vendor="Contentreich" \
      author="Andreas Steffan <a.steffan@contentreich.de>" \
      description="Solr gitopsed and parathesed" \
      version="0.1"

COPY . /build
WORKDIR /build
# TODO
RUN curl -s https://raw.githubusercontent.com/babashka/babashka/master/install -o install && \
    chmod 755 install && \
    ./install && \
    ./test-runner.clj && \
    bb build

FROM babashka/babashka:latest
RUN mkdir /work
WORKDIR /work
COPY --from=build /build/target/*.clj /build/config-default.edn /
RUN apt-get update && apt-get install -y --no-install-recommends git openssh-client && \
    apt-get clean
ENV config=/config-default.edn
# CMD [ "bb", "/satrn.clj", "sync" ]
