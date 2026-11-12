DATE=$(date +%Y-%m-%d)
if [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi
CPUS="${BENCHMARK_CPUS:-6-11}"
RTT_CPUS="${BENCHMARK_RTT_CPUS:-6-11,18-23}"
taskset -c "$RTT_CPUS" "$JAVA" -jar target/benchmarks.jar -rf json -prof gc FixEngineRTTBenchmark
mv jmh-result.json "jmh-result-FixEngineRTTBenchmark-${DATE}.json"
mv "jmh-result-FixEngineRTTBenchmark-${DATE}.json" results