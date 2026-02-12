FROM liquibase:latest

RUN lpm add postgresql --global

COPY ./liquibase/migration/ /liquibase/changelog/liquibase

CMD ["sh", "-c", "docker-entrypoint.sh --url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME} --username=${DB_USER} --password=${DB_PASS} --classpath=/liquibase/changelog --changelog-file=liquibase/${LIQUI_CHANGELOG} --liquibase-schema-name=public --driver=org.postgresql.Driver --logLevel=info update"]
