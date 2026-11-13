#!/system/bin/sh

# Paths
box_dir="/data/adb/box"
box_run="${box_dir}/run"
box_pid="${box_run}/box.pid"

# busybox in PATH, the message language and t()
. "${box_dir}/scripts/box.common"

run_as_su() {
    su -c "$1"
}

stop_service() {
    echo "$(t 'Service is shutting down' 'Сервис останавливается')"
    run_as_su "${box_dir}/scripts/box.iptables disable"
    run_as_su "${box_dir}/scripts/box.service stop"
}

start_service() {
    echo "$(t 'Service is starting, please wait for a moment' 'Сервис запускается, подождите немного')"
    run_as_su "${box_dir}/scripts/box.service start"
    run_as_su "${box_dir}/scripts/box.iptables enable"
}

if [ -f "${box_pid}" ]; then
    PID=$(cat "${box_pid}")
    if [ -e "/proc/${PID}" ]; then
        stop_service
    else
        start_service
    fi
else
    start_service
fi