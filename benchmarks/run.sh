DATE=$(date +%Y-%m-%d)
if [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi
"$JAVA" -jar target/benchmarks.jar -rf json -prof gc FloatSerDeBenchmark
mv jmh-result.json "jmh-result-FloatSerDeBenchmark-${DATE}.json"
mv "jmh-result-FloatSerDeBenchmark-${DATE}.json" results
"$JAVA" -jar target/benchmarks.jar -rf json -prof gc IntSerDeBenchmark
mv jmh-result.json "jmh-result-IntSerDeBenchmark-${DATE}.json"
mv "jmh-result-IntSerDeBenchmark-${DATE}.json" results
"$JAVA" -jar target/benchmarks.jar -rf json -prof gc LongSerDeBenchmark
mv jmh-result.json "jmh-result-LongSerDeBenchmark-${DATE}.json"
mv "jmh-result-LongSerDeBenchmark-${DATE}.json" results
"$JAVA" -jar target/benchmarks.jar -rf json -prof gc ClockBenchmark
mv jmh-result.json "jmh-result-ClockBenchmark-${DATE}.json"
mv "jmh-result-ClockBenchmark-${DATE}.json" results
"$JAVA" -jar target/benchmarks.jar -rf json -prof gc IdGenerationBenchmark
mv jmh-result.json "jmh-result-IdGenerationBenchmark-${DATE}.json"
mv "jmh-result-IdGenerationBenchmark-${DATE}.json" results
"$JAVA" -jar target/benchmarks.jar -rf json -prof gc FixEngineRTTBenchmark
mv jmh-result.json "jmh-result-FixEngineRTTBenchmark-${DATE}.json"
mv "jmh-result-FixEngineRTTBenchmark-${DATE}.json" results
"$JAVA" -jar target/benchmarks.jar -rf json -prof gc OtlpHttpSenderBenchmark
mv jmh-result.json "jmh-result-OtlpHttpSenderBenchmark-${DATE}.json"
mv "jmh-result-OtlpHttpSenderBenchmark-${DATE}.json" results
"$JAVA" -jar target/benchmarks.jar -rf json -prof gc OtlpTracingExportBenchmark
mv jmh-result.json "jmh-result-OtlpTracingExportBenchmark-${DATE}.json"
mv "jmh-result-OtlpTracingExportBenchmark-${DATE}.json" results
"$JAVA" -jar target/benchmarks.jar -rf json -prof gc OtlpGrpcSenderBenchmark
mv jmh-result.json "jmh-result-OtlpGrpcSenderBenchmark-${DATE}.json"
mv "jmh-result-OtlpGrpcSenderBenchmark-${DATE}.json" results
#"$JAVA" -jar target/benchmarks.jar -rf json -prof gc -prof jfr