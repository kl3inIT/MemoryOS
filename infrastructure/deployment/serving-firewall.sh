#!/bin/sh
# Admits only the application node to the ports the serving node publishes.
#
# ufw cannot do this: Docker writes its own NAT and FORWARD rules, so a published port answers the
# whole private subnet whatever ufw says. Docker leaves the DOCKER-USER chain to the operator and
# consults it before its own rules. By then a published port has been rewritten to the container's
# address, so each rule matches the port the client asked for through conntrack.
#
# Usage: serving-firewall.sh <allowed source address> <port>...
# Installed as /usr/local/sbin/memoryos-serving-firewall and run by memoryos-serving-firewall.service.
set -eu

[ "$#" -ge 2 ] || { echo "usage: $0 <allowed source address> <port>..." >&2; exit 64; }
allowed=$1
shift

# Our own chain is rebuilt on every run, and the rules that jump to it are replaced, so a restart
# never stacks duplicates and a removed port stops being filtered.
iptables -N MEMORYOS-SERVING 2>/dev/null || iptables -F MEMORYOS-SERVING
iptables -A MEMORYOS-SERVING -s "$allowed" -j RETURN
iptables -A MEMORYOS-SERVING -j DROP

iptables -N DOCKER-USER 2>/dev/null || true
iptables -S DOCKER-USER | grep -- '-j MEMORYOS-SERVING' | sed 's/^-A DOCKER-USER //' | while read -r rule; do
    # shellcheck disable=SC2086 # the saved rule is split back into its arguments
    iptables -D DOCKER-USER $rule
done
for port in "$@"; do
    iptables -I DOCKER-USER -p tcp -m conntrack --ctorigdstport "$port" --ctdir ORIGINAL -j MEMORYOS-SERVING
done
