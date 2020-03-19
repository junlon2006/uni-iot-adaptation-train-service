FROM openjdk:8-jre
MAINTAINER junlon2006 <junlon2006@163.com>

ENV JAVA_OPTS="-server -Xmx1024M -Xms1024M -Xloggc:/opt/logs/gc.log -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ParallelRefProcEnabled \
        -XX:ErrorFile=/opt/logs/hs_err_pid%p.log \
        -XX:HeapDumpPath=/opt/logs -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+HeapDumpOnOutOfMemoryError \
        -Djava.io.tmpdir=/opt/temp \
        -XX:+PrintHeapAtGC \
        -XX:MaxMetaspaceSize=256M -XX:MetaspaceSize=128M -Dfile.encoding=UTF-8 -Duser.timezone=GMT+8" \
    SERVER_PORT=29528

RUN mkdir -p /opt/logs /opt/temp
EXPOSE ${SERVER_PORT}

HEALTHCHECK --interval=10s --timeout=3s \
        CMD curl -v --fail http://localhost:${SERVER_PORT} || exit 1

WORKDIR /opt
ENV TZ "Asia/Shanghai"
ENV LANG en_US.UTF-8
ENV LC_ALL en_US.UTF-8
ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -Djava.library.path=/usr/lib:/usr/local/lib:/opt/lib -jar adaptation-train-1.0.0.jar --spring.profiles.active=prod"]
ADD adaptation-train-1.0.0.jar /opt/adaptation-train-1.0.0.jar
ADD grammar /opt/grammar
ADD lib /opt/lib
ADD models /opt/models
ADD application-prod.properties /opt/application-prod.properties