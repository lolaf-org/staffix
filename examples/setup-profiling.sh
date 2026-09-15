# to execute for accurate profiling examples (running examples with -pe)
sudo sysctl kernel.perf_event_paranoid=1
sudo sysctl kernel.kptr_restrict=0
echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope
