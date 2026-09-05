#!/usr/bin/env bash
# Flags Kotlin classes that declare a property AFTER an `init {}` block. If the init block
# (or a constructor call made from it) reads that property, the class crashes at construction
# with a null field. JVM unit tests never construct Views, so only a device run catches it;
# this scan catches it before the push. Usage: scan-view-init-order.sh <file.kt>...
status=0
for f in "$@"; do
  awk -v f="$f" '
    /^ *(private |internal |public )?(inner |open |abstract )?class / { cls=$0; ininit=0; seeninit=0; sub(/^ +/, "", cls) }
    /^ *init \{/ { ininit=1; seeninit=1; next }
    ininit && /^ *\}/ { ininit=0; next }
    seeninit && !ininit && /^ *(private |internal |protected )?(val|var) [A-Za-z_]+ *(:|=)/ {
      print f ": property declared after an init block in " cls " -> " $0; found=1 }
    END { exit found ? 1 : 0 }
  ' "$f" || status=1
done
exit $status
