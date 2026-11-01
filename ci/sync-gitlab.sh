#!/usr/bin/env bash
set -euo pipefail

: "${GITLAB_TOKEN:?Add the GITLAB_TOKEN GitHub Actions secret.}"
: "${GITLAB_REPOSITORY_URL:?Add the GITLAB_REPOSITORY_URL GitHub Actions variable.}"

# A clean GitLab HTTPS destination; never embed credentials in a remote URL.
if [[ ! "$GITLAB_REPOSITORY_URL" =~ ^https://gitlab\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+\.git$ ]]; then
  printf '%s\n' 'GITLAB_REPOSITORY_URL must be a GitLab.com HTTPS clone URL ending in .git without credentials or query parameters.' >&2
  exit 1
fi

umask 077
melkit_askpass="$(mktemp)"
trap 'rm -f "$melkit_askpass"' EXIT
cat > "$melkit_askpass" <<'ASKPASS'
#!/usr/bin/env bash
case "$1" in
  *Username*|*username*) printf '%s\n' oauth2 ;;
  *Password*|*password*) printf '%s\n' "$GITLAB_TOKEN" ;;
  *) exit 1 ;;
esac
ASKPASS
chmod 700 "$melkit_askpass"
export GIT_ASKPASS="$melkit_askpass"
export GIT_TERMINAL_PROMPT=0

# Non-forced, one-branch push. GitLab must start empty or share this history.
# Credentials stay in the process environment, not the command line or repo.
git -c credential.helper= -c core.hooksPath=/dev/null push \
  "$GITLAB_REPOSITORY_URL" HEAD:refs/heads/main
