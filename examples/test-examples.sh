#!/bin/bash

EX_DIR=$SORCER_HOME/examples
LOG_DIR=/tmp/logs/

stopSorcer ( ) {
  for p in `jps | grep ServiceStarter | cut -d " " -f 1`; do
    kill -9 $p > /dev/null
    if [ "$USE_RIO" == "1" ]; then
        if [ -f $SORCER_HOME/logs/all-$p.log ]; then
            mv $SORCER_HOME/logs/all-$p.log $1/
        fi
    fi

  done
}

startSorcer ( ) {
if [ "$USE_RIO" == "1" ]; then
  $SORCER_HOME/bin/rio-boot > $1 &
  sleep 12
  $SORCER_HOME/bin/rio deploy $SORCER_HOME/configs/SorcerBoot.groovy > $1_deploy &
  sleep 13
else
  #$SORCER_HOME/bin/sorcer-boot > $1 &
  ant -f $SORCER_HOME/bin/sorcer-boot.xml > $1 &
  sleep 8
fi
}

restartSorcer ( ) {
  stopSorcer  $2
  startSorcer $1
}

showExceptions ( ) {
  cd $LOG_DIR
  grep -R -n -A 5 Exception: > exceptions.txt
  grep -R -n -B 10 FAILURE >> exceptions.txt
  cat exceptions.txt
}

cleanLogs ( ) {
  rm -rf $LOG_DIR
  mkdir $LOG_DIR  
}

ex0 ( ) {
  EX=ex0
  mkdir $LOG_DIR/$EX
  restartSorcer $LOG_DIR/$EX/socer-$EX$1.log $LOG_DIR/$EX/
  if [ "$1" == "rio" ]; then
    $SORCER_HOME/bin/rio deploy $SORCER_HOME/examples/ex0/ex0-prv/Ex0Boot.groovy
  else
    cd $EX_DIR/$EX/$EX-prv/
    ant -f boot.xml > $LOG_DIR/$EX/ex0-prv-run.log &
  fi
  sleep 8
  cd $EX_DIR/$EX/$EX-req/
  ant -f run.xml > $LOG_DIR/$EX/req$1.log
  ./run.ntl >> $LOG_DIR/$EX/req$1.log
}

ex1 ( ) {
  ## ex1 run-bean
  # Enabling tests on this module will generate RuntimeExceptions thatT are
  # thrown on purpose by the second provider (whoIsIt2...)
  EX=ex1
  mkdir $LOG_DIR/$EX
  restartSorcer $LOG_DIR/$EX/run-bean-socer-$EX.log $LOG_DIR/$EX/
  cd $EX_DIR/$EX/run-bean/bin/
  ant -f whoIsItBean1-prv-run.xml > $LOG_DIR/$EX/run-bean-whoIsItBean1-prv.log &
  #ant -f whoIsItBean2-prv-run.xml > $LOG_DIR/$EX/run-bean-whoIsItBean2-prv.log &
  sleep 5
  ant -f whoIsItBean1-req-run.xml > $LOG_DIR/$EX/run-bean_req.log 
  ant -f whoIsItBean2-req-run.xml >> $LOG_DIR/$EX/run-bean_req.log
  ant -f whoIsItBean1-app-run.xml >> $LOG_DIR/$EX/run-bean_req.log
  ant -f whoIsItBean2-app-run.xml >> $LOG_DIR/$EX/run-bean_req.log

  ## ex1 run-prv boot
  restartSorcer $LOG_DIR/$EX/run-prv-boot-socer-$EX.log $LOG_DIR/$EX/
  cd $EX_DIR/$EX/run-prv/bin/
  ant -f whoIsIt-prvs-boot.xml > $LOG_DIR/$EX/run-prv-boot-whoIsIt.log &
  sleep 5
  ant -f whoIsItBatchTask-app-run.xml > $LOG_DIR/$EX/run-prv_boot_req.log
  ant -f whoIsItTask-app-run.xml >> $LOG_DIR/$EX/run-prv_boot_req.log
  ant -f whoIsItSeqTask-app-run.xml >> $LOG_DIR/$EX/run-prv_boot_req.log
  ant -f whoIsItParTask-app-run.xml >> $LOG_DIR/$EX/run-prv_boot_req.log
  ant -f whoIsItPushJob-app-run.xml >> $LOG_DIR/$EX/run-prv_boot_req.log
  ant -f whoIsItPullJob-app-run.xml >> $LOG_DIR/$EX/run-prv_boot_req.log

  ## ex1 run-prv run
  restartSorcer $LOG_DIR/$EX/run-prv-socer-$EX.log $LOG_DIR/$EX/
  cd $EX_DIR/$EX/run-prv/bin/
  ant -f whoIsIt1-prv-run.xml > $LOG_DIR/$EX/run-prv-whoIsIt1.log &
  ant -f whoIsIt2-prv-run.xml > $LOG_DIR/$EX/whoIsIt2-prv.log &
  sleep 5
  ant -f whoIsItBatchTask-app-run.xml > $LOG_DIR/$EX/run-prv_req.log
  ant -f whoIsItTask-app-run.xml >> $LOG_DIR/$EX/run-prv_req.log
  ant -f whoIsItSeqTask-app-run.xml >> $LOG_DIR/$EX/run-prv_req.log
  ant -f whoIsItParTask-app-run.xml >> $LOG_DIR/$EX/run-prv_req.log
  ant -f whoIsItPushJob-app-run.xml >> $LOG_DIR/$EX/run-prv_req.log
  ant -f whoIsItPullJob-app-run.xml >> $LOG_DIR/$EX/run-prv_req.log  
}

ex2 ( ) {
  EX=ex2
  mkdir $LOG_DIR/$EX
  restartSorcer $LOG_DIR/$EX/socer-$EX.log $LOG_DIR/$EX/
  cd $EX_DIR/$EX/$EX-prv/
  ant -f worker1-prv-run.xml > $LOG_DIR/$EX/worker1-prv-run.log &
  ant -f worker2-prv-run.xml > $LOG_DIR/$EX/worker2-prv-run.log &
  ant -f worker3-prv-run.xml > $LOG_DIR/$EX/worker3-prv-run.log &
  sleep 5
  cd $EX_DIR/$EX/$EX-req/
  ant -f worker-task-app-run.xml > $LOG_DIR/$EX/req.log
  ant -f worker-singleton-app-run.xml >> $LOG_DIR/$EX/req.log
  ant -f worker-job-app-run.xml >> $LOG_DIR/$EX/req.log
}


ex3 ( ) {
  EX=ex3
  mkdir $LOG_DIR/$EX
  restartSorcer $LOG_DIR/$EX/socer-$EX.log $LOG_DIR/$EX/
  cd $EX_DIR/ex2/ex2-prv/
  ant -f worker1-prv-run.xml > $LOG_DIR/$EX/worker1-prv-run.log &
  ant -f worker2-prv-run.xml > $LOG_DIR/$EX/worker2-prv-run.log &
  ant -f worker3-prv-run.xml > $LOG_DIR/$EX/worker3-prv-run.log &
  sleep 8
  cd $EX_DIR/$EX/$EX-req/
  ant -f context-worker-req-run.xml > $LOG_DIR/$EX/req.log
  ant -f piped-worker-req-run.xml >> $LOG_DIR/$EX/req.log
  ant -f strategy-worker-req-run.xml >> $LOG_DIR/$EX/req.log
}

ex4 ( ) {
  EX=ex4
  mkdir $LOG_DIR/$EX
  restartSorcer $LOG_DIR/$EX/socer-$EX.log $LOG_DIR/$EX/
  cd $EX_DIR/ex2/ex2-prv/
  ant -f worker1-prv-run.xml > $LOG_DIR/$EX/worker1-prv-run.log &
  ant -f worker2-prv-run.xml > $LOG_DIR/$EX/worker2-prv-run.log &
  ant -f worker3-prv-run.xml > $LOG_DIR/$EX/worker3-prv-run.log &
  sleep 8
  cd $EX_DIR/$EX/$EX-req/
  ant -f seqJob-req-run.xml > $LOG_DIR/$EX/req.log
  ant -f parPullMasterJob-req-run.xml >> $LOG_DIR/$EX/req.log
  ant -f seqPushMasterJob-req-run.xml >> $LOG_DIR/$EX/req.log
}

ex5 ( ) {
  TYPE=$1
  EX=ex5
  mkdir $LOG_DIR/$EX
  restartSorcer $LOG_DIR/$EX/socer-$EX-$TYPE.log $LOG_DIR/$EX/
  cd $EX_DIR/$EX/$EX-prv/
  ant -f arithmetic-$TYPE.xml > $LOG_DIR/$EX/$TYPE-arithmetic.log &
  cd $EX_DIR/$EX/$EX-req/
  ant -f arithmetic-ter-run.xml > $LOG_DIR/$EX/$TYPE-arithmetic-ter-run.log &
  sleep 8
  mvn -Dmaven.test.skip=false -DskipTests=false test > $LOG_DIR/$EX/$TYPE-req.log    
}

ex6 ( ) {
  TYPE=$1
  EX=ex6
  mkdir $LOG_DIR/$EX
  restartSorcer $LOG_DIR/$EX/socer-$EX-$TYPE.log $LOG_DIR/$EX/

  if [ "$1" == "rio" ]; then
    $SORCER_HOME/bin/rio deploy $SORCER_HOME/examples/ex6/ex6-prv/Ex6Boot.groovy
  else
      cd $EX_DIR/$EX/$EX-prv/
      ant -f arithmetic-$TYPE.xml > $LOG_DIR/$EX/$TYPE-arithmetic.log &
  fi
  cd $EX_DIR/$EX/$EX-req/
  ant -f arithmetic-ter-run.xml > $LOG_DIR/$EX/$TYPE-arithmetic-ter-run.log &
  sleep 8
  mvn -Dmaven.test.skip=false -DskipTests=false test > $LOG_DIR/$EX/$TYPE-req.log 
  ant -f f5-req-run.xml > $LOG_DIR/$EX/$TYPE-f5-req.log
  ant -f f5a-req-run.xml >> $LOG_DIR/$EX/$TYPE-f5a-req.log
  ant -f f5m-req-run.xml >> $LOG_DIR/$EX/$TYPE-f5m-req.log
  ant -f f5pull-req-run.xml >> $LOG_DIR/$EX/$TYPE-f5pull-req.log
  ant -f f5xP-req-run.xml >> $LOG_DIR/$EX/$TYPE-f5xP-req.log
  ant -f f5xS-req-run.xml >> $LOG_DIR/$EX/$TYPE-f5xS-req.log
  ant -f f1-req-run.xml > $LOG_DIR/$EX/$TYPE-f1-req.log
  ant -f f1-PAR-pull-run.xml >> $LOG_DIR/$EX/$TYPE-f1-PAR-req.log
  ant -f f1-SEQ-pull-run.xml >> $LOG_DIR/$EX/$TYPE-f1-SEQ-req.log
  nsh f1.ntl > $LOG_DIR/$EX/$TYPE-ntl-req.log    
}

if [ "$1" == "exc" ]; then
  showExceptions
  exit
fi

if [ "$1" == "rio" ]; then
  USE_RIO=1
fi

cleanLogs
ex0
if [ "$1" == "rio" ]; then
  ex0 rio
fi
ex1
ex2
ex3
ex4
ex5 all-beans-boot
ex5 all-prv-boot
ex5 bean-boot
ex6 all-beans-boot
ex6 all-prvs-run
ex6 prv-boot
if [ "$1" == "rio" ]; then
  ex6 rio
fi

stopSorcer
showExceptions
