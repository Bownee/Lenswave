#!/usr/bin/env bash
# Flags Kotlin classes that declare a member property AFTER an `init {}` block. If the init
# block (or a constructor call made from it) reads that property, the class crashes at
# construction with a null field. JVM unit tests never construct Views, so only a device run
# catches it; this scan catches it before the push. Usage: scan-view-init-order.sh <file.kt>...
# Exit 1 when anything was flagged. Only members at the class's own indentation count, the
# context closes at the class's closing brace, and `by lazy` members are skipped (they are
# initialised on first read, so order does not matter for them).
status=0
for f in "$@"; do
  awk -v f="$f" '
    function indent(s) { match(s, /^ */); return RLENGTH }
    /^ *(private |internal |public |protected )?(inner |open |abstract |data |sealed )*class [A-Za-z]/ {
      cls = $0; sub(/^ +/, "", cls); sub(/[({].*$/, "", cls)
      classIndent = indent($0); member = classIndent + 4; ininit = 0; seeninit = 0; next
    }
    ininit && indent($0) == member && /^ *}/ { ininit = 0; next }
    !ininit && member > 0 && indent($0) == classIndent && /^ *}/ { member = -1; seeninit = 0; next }
    ininit { next }
    indent($0) == member && /^ *init \{/ { ininit = 1; seeninit = 1; next }
    seeninit && indent($0) == member && /^ *(private |internal |protected |public )?(lateinit )?(val|var) [A-Za-z_][A-Za-z0-9_]* *(:|=)/ && !/ by lazy/ {
      print f ": member declared after an init block in " cls " -> " $0; found = 1
    }
    END { exit found ? 1 : 0 }
  ' "$f" || status=1
done
exit $status
