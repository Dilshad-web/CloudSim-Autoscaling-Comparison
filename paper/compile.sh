#!/usr/bin/env bash
set -uo pipefail

# Build the Springer Nature manuscript. Run this script from its own directory.
cd "$(dirname "$0")"

run_pdflatex() {
  local pass_log="$1"
  local status=0
  pdflatex -file-line-error -interaction=nonstopmode sn-article.tex > "$pass_log" 2>&1 || status=$?

  # The first LaTeX pass can return a non-zero status while citations/references
  # are still unresolved. Continue through BibTeX and later passes, but never
  # ignore a real TeX error.
  if grep -Eq '^!|LaTeX Error:|Emergency stop' "$pass_log"; then
    echo "Fatal LaTeX error during $pass_log" >&2
    cat "$pass_log" >&2
    return 1
  fi
  if [[ ! -s sn-article.pdf ]]; then
    echo "No PDF was produced during $pass_log" >&2
    cat "$pass_log" >&2
    return 1
  fi
  # Some TeX distributions return a non-zero process status for recoverable
  # diagnostics even when a PDF is produced. Fatal TeX errors are checked above;
  # unresolved citations/references are checked after the final pass.
  : "$status"
}

run_pdflatex build-pass1.log || exit 1
if ! bibtex8 sn-article > build-bib.log 2>&1; then
  echo "BibTeX failed; see build-bib.log" >&2
  cat build-bib.log >&2
  exit 1
fi
run_pdflatex build-pass2.log || exit 1
run_pdflatex build-pass3.log || exit 1

if grep -Eq 'Citation .*undefined|Reference .*undefined|There were undefined (citations|references)' build-pass3.log; then
  echo "Unresolved citations or references remain; inspect build-pass3.log" >&2
  exit 1
fi

echo "Build completed: $(pwd)/sn-article.pdf"
