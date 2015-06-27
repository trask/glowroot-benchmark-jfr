FROM glowroot/glowroot-benchmark

ENV MAVEN_MAJOR_VERSION 3
ENV MAVEN_VERSION 3.3.3
ENV JMC_VERSION 5.5.0.165303

RUN apt-key adv --keyserver keyserver.ubuntu.com --recv-keys EEA14886 \
  && echo "deb http://ppa.launchpad.net/webupd8team/java/ubuntu trusty main" > /etc/apt/sources.list.d/webupd8team-java.list \
  && echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | debconf-set-selections \
  && apt-get update \
  && apt-get -y install oracle-java8-installer \
  && rm -rf /var/cache/oracle-jdk8-installer \
  && rm -r /var/lib/apt/lists/*

COPY pom.xml /workspace/
COPY src /workspace/src/
RUN apt-get update \
  && apt-get -y install curl \
  && curl http://archive.apache.org/dist/maven/maven-$MAVEN_MAJOR_VERSION/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz \
       | tar xzf - -C /usr/share \
  && mv /usr/share/apache-maven-$MAVEN_VERSION /usr/share/maven \
  && ln -s /usr/share/maven/bin/mvn /usr/bin/mvn \
  && mvn install:install-file \
       -Dfile=/usr/lib/jvm/java-8-oracle/lib/missioncontrol/plugins/com.jrockit.mc.common_$JMC_VERSION.jar \
       -DgroupId=com.oracle.jmc \
       -DartifactId=jmc-commons \
       -Dversion=$JMC_VERSION \
       -Dpackaging=jar \
  && mvn install:install-file \
       -Dfile=/usr/lib/jvm/java-8-oracle/lib/missioncontrol/plugins/com.jrockit.mc.flightrecorder_$JMC_VERSION.jar \
       -DgroupId=com.oracle.jmc \
       -DartifactId=jmc-flightrecorder \
       -Dversion=$JMC_VERSION \
       -Dpackaging=jar \
  && (cd /workspace && mvn -Djmc.version=$JMC_VERSION package) \
  && cp /workspace/target/analyzer.jar / \
  && rm -r /workspace \
  && rm -r ~/.m2 \
  && rm -r /usr/share/maven \
  && rm /usr/bin/mvn \
  && apt-get -y purge --auto-remove curl \
  && rm -r /var/lib/apt/lists/*

RUN cp /usr/lib/jvm/java-8-oracle/jre/lib/jfr/default.jfc . \
  && sed -i 's#<setting name="period" control="method-sampling-interval">20 ms</setting>#<setting name="period" control="method-sampling-interval">10 ms</setting>#' default.jfc

RUN mkdir -p glowroot && echo '{"general":{"profilingIntervalMillis":0},"advanced":{"timerWrapperMethods":true,"captureThreadInfo":false,"captureGcInfo":false}}' > glowroot/config.json

ENV JVM_ARGS \
  -Xms1g \
  -Xmx1g \
  -XX:+UnlockCommercialFeatures \
  -XX:+FlightRecorder \
  -XX:StartFlightRecording=settings=default.jfc,delay=20s,duration=600s,filename=data/prof.jfr \
  -XX:FlightRecorderOptions=stackdepth=1000 \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:+DebugNonSafepoints \
  -Dglowroot.internal.dummyTicker=true

ENV JMH_ARGS -f 1 -i 600 ServletWithGlowrootBenchmark

CMD ./docker-entrypoint.sh && java -Xmx1g -jar analyzer.jar data/prof.jfr | tee data/prof.txt
