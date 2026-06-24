#!/usr/bin/env sh
##############################################################################
# Gradle start up script for UNIX
##############################################################################

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xmx256m"'

APP_HOME=$(cd "$(dirname "$0")" && pwd)
APP_NAME="Gradle"
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" $DEFAULT_JVM_OPTS -Dorg.gradle.appname="$APP_NAME" \
  -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
