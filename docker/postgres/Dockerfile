FROM postgres:alpine

COPY ./postgres/*.sh /docker-entrypoint-initdb.d/
COPY ./postgres/postgresql.conf /etc/postgresql/postgresql.conf

CMD ["postgres", "-c", "config_file=/etc/postgresql/postgresql.conf"]
