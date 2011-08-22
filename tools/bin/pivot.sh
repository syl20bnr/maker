#!/bin/sh 

M2DIR=~/.m2/repository
SCRIPTDIR=`dirname $0`
RESOURCESDIR=$SCRIPTDIR/../../core/resources

CP=$M2DIR/starling/core/1.0/core-1.0.jar
CP=$CP:$M2DIR/org/scala-lang/scala-library/2.7.7/scala-library-2.7.7.jar
CP=$CP:$M2DIR/org/scala-lang/scala-swing/2.7.7/scala-swing-2.7.7.jar
CP=$CP:$M2DIR/org/scala-lang/2.7.7/scala-compiler-2.7.7.jar
CP=$CP:$M2DIR/org/springframework/spring-core/2.5.6/spring-core-2.5.6.jar
CP=$CP:$M2DIR/org/springframework/spring-context/2.5.6/spring-context-2.5.6.jar
CP=$CP:$M2DIR/org/springframework/spring-context-support/2.5.6/spring-context-support-2.5.6.jar
CP=$CP:$M2DIR/org/springframework/spring-jdbc/2.5.6/spring-jdbc-2.5.6.jar
CP=$CP:$M2DIR/org/springframework/spring-tx/2.5.6/spring-tx-2.5.6.jar
CP=$CP:$M2DIR/org/springframework/spring-beans/2.5.6/spring-beans-2.5.6.jar
CP=$CP:$M2DIR/commons-logging/commons-logging/1.0.4/commons-logging-1.0.4.jar
CP=$CP:$M2DIR/commons-dbutils/commons-dbutils/1.3/commons-dbutils-1.3.jar
CP=$CP:$M2DIR/c3p0/c3p0/0.9.1.2/c3p0-0.9.1.2.jar
CP=$CP:$M2DIR/org/scala-tools/javautils/2.7.4-0.1/javautils-2.7.4-0.1.jar
CP=$CP:$M2DIR/joda-time/joda-time/1.6/joda-time-1.6.jar
CP=$CP:$M2DIR/commons-beanutils/commons-beanutils/1.7.0/commons-beanutils-1.7.0.jar
CP=$CP:$M2DIR/log4j/log4j/1.2.13/log4j-1.2.13.jar
CP=$CP:$M2DIR/mysql/mysql-connector-java/5.1.6/mysql-connector-java-5.1.6.jar
CP=$CP:$RESOURCESDIR/spring

MAINCLS=starling.pivot.view.swing.Main

java -cp $CP $@ $MAINCLS

exit 0