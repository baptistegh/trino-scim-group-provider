# Trino image with the locally built plugin. Build the plugin first with: ./mvnw package
ARG TRINO_VERSION=476

FROM alpine:3.24 AS plugin
COPY target/trino-scim-group-provider-*.zip /tmp/
RUN mkdir /plugin && unzip -q -j /tmp/trino-scim-group-provider-*.zip -d /plugin

FROM trinodb/trino:${TRINO_VERSION}
COPY --from=plugin /plugin /usr/lib/trino/plugin/scim-group-provider
