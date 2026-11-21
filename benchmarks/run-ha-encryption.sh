DATE=$(date +%Y-%m-%d)
if [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi
CPUS="${BENCHMARK_CPUS:-6-11}"
RTT_CPUS="${BENCHMARK_RTT_CPUS:-6-11,18-23}"
JAVA_MAJOR=$("$JAVA" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')
for PROVIDER in \
    "PROVIDER_JDK CLEAR,AES_GCM_128,AES_GCM_256,CHACHA20_POLY1305,AES_CTR_HMAC_SHA256,CHACHA20_HMAC_SHA256" \
    "PROVIDER_CORRETTO AES_GCM_128,AES_GCM_256" \
    "PROVIDER_BC AES_GCM_128,AES_GCM_256,CHACHA20_POLY1305,CHACHA20_HMAC_SHA256" \
    "PROVIDER_BC_LTS AES_GCM_128,AES_GCM_256,CHACHA20_POLY1305"; do
  set -- $PROVIDER
  taskset -c "$CPUS" "$JAVA" -jar target/benchmarks.jar -rf json -prof gc HaFrameCipherBenchmark -p provider="$1" -p encryption="$2"
  mv jmh-result.json "results/jmh-result-HaFrameCipherBenchmark-$1-jdk${JAVA_MAJOR}-${DATE}.json"
done
if [ "$JAVA_MAJOR" -lt 17 ]; then
  exit 0
fi
RTT="-p mode=SYNC_APPLIED -p followerStore=FOLLOWER_MEMORY -p busySession=NO_BUSY_SESSION -p replicationWaiting=BACKOFF,ALL_SPIN"
taskset -c "$RTT_CPUS" "$JAVA" -jar target/benchmarks.jar -rf json -prof gc HaRttBenchmark $RTT -p encryption=CLEAR,AES_GCM_256,AES_CTR_HMAC_SHA256,CHACHA20_HMAC_SHA256 -p messageEncryption=ENCRYPTED_MESSAGES,UNENCRYPTED_MESSAGES
mv jmh-result.json "results/jmh-result-HaRttBenchmark-encryption-PROVIDER_JDK-${DATE}.json"
taskset -c "$RTT_CPUS" "$JAVA" -jar target/benchmarks.jar -rf json -prof gc HaRttBenchmark $RTT -p provider=PROVIDER_CORRETTO -p encryption=AES_GCM_128,AES_GCM_256
mv jmh-result.json "results/jmh-result-HaRttBenchmark-encryption-PROVIDER_CORRETTO-${DATE}.json"
taskset -c "$RTT_CPUS" "$JAVA" -jar target/benchmarks.jar -rf json -prof gc HaLoadBenchmark -p sessions=SESSIONS_16 -p mode=SYNC_RECEIVED -p rate=RATE_10000_PER_S,RATE_50000_PER_S,RATE_200000_PER_S -p encryption=CLEAR,AES_GCM_256,AES_CTR_HMAC_SHA256 2>&1 | tee "results/HaLoadBenchmark-encryption-${DATE}.log"
mv jmh-result.json "results/jmh-result-HaLoadBenchmark-encryption-${DATE}.json"
