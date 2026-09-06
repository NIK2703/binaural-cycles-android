#!/usr/bin/env bash
# git_push.sh — sandbox-aware push for binaural-cycles-android
#
# Why this exists:
#   This repo is worked on inside an agent sandbox where git refs do NOT persist
#   (branch loose-refs get reset), and porcelain `git rebase`/`git cherry-pick`
#   fail with "cannot rebase: You have unstaged changes" even on a clean tree
#   (CRLF normalization / phantom dirty trees). A plain `git push` is therefore
#   fragile. This script removes every fragile piece:
#     * resolves the true local tip even if the loose-ref was reset (reflog fallback)
#     * on divergence, rebases via `git commit-tree` plumbing (no porcelain rebase)
#     * pushes the literal SHA (never $(git rev-parse HEAD))
#     * rewrites BOTH .git/refs/heads/<b> and .git/refs/remotes/<remote>/<b>
#       loose refs after a successful push (sandbox ref-persistence quirk)
#   It deliberately does NOT touch `git checkout .` or `git rebase`/`cherry-pick`.
#
# Usage:
#   git pushsafe                 # push committed work (FF or auto-rebase); leaves
#                                #   uncommitted changes in the working tree as-is
#   git pushsafe --commit -m "msg"  # commit ALL changes first, then push
#   git pushsafe --force         # force-push (rewrites remote history) — dangerous
#   git pushsafe <branch> [remote]  # explicit branch / remote
set -uo pipefail

cd "$(git rev-parse --show-toplevel)" || { echo "ERROR: not a git repo"; exit 1; }

# helper: persist both loose refs after a successful push, sync tree only if clean
write_refs() {
  local tip="$1"
  mkdir -p "$(dirname ".git/refs/heads/$BRANCH")" "$(dirname ".git/refs/remotes/$REMOTE/$BRANCH")"
  printf '%s\n' "$tip" > ".git/refs/heads/$BRANCH"
  printf '%s\n' "$tip" > ".git/refs/remotes/$REMOTE/$BRANCH"
  if git diff --quiet HEAD -- && [[ -z "$(git status --porcelain)" ]]; then
    git reset --hard "$tip" >/dev/null 2>&1 || true
    echo "Local tree synced to $tip"
  else
    echo "WARN: working tree has uncommitted changes; left them as-is."
    echo "      Branch ref updated to $tip (run 'git status' to inspect)."
  fi
  echo "OK -> $tip"
}

BRANCH=""
REMOTE="origin"
AUTO_COMMIT=0
FORCE=0
MSG=""
ARGS=("$@")
i=0
while [[ $i -lt ${#ARGS[@]} ]]; do
  a="${ARGS[$i]}"
  case "$a" in
    --commit) AUTO_COMMIT=1 ;;
    --force|-f) FORCE=1 ;;
    -m) i=$((i+1)); MSG="${ARGS[$i]:-}"; AUTO_COMMIT=1 ;;
    -m*) MSG="${a#-m}"; AUTO_COMMIT=1 ;;
    --) i=$((i+1)); while [[ $i -lt ${#ARGS[@]} ]]; do
          if [[ -z "$BRANCH" ]]; then BRANCH="${ARGS[$i]}"; else REMOTE="${ARGS[$i]}"; fi
          i=$((i+1)); done ;;
    *) if [[ -z "$BRANCH" ]]; then BRANCH="$a"; else REMOTE="$a"; fi ;;
  esac
  i=$((i+1))
done

if [[ -z "$BRANCH" ]]; then BRANCH="$(git branch --show-current)"; fi
if [[ -z "$BRANCH" ]]; then echo "ERROR: cannot determine current branch"; exit 1; fi

GIT_USER="${GIT_USER:-Nikita}"
GIT_EMAIL="${GIT_EMAIL:-nikita@local}"

# ---- optional auto-commit ---------------------------------------------------
if [[ $AUTO_COMMIT -eq 1 ]]; then
  if ! git diff --quiet HEAD -- || [[ -n "$(git status --porcelain)" ]]; then
    git add -A
    git -c user.name="$GIT_USER" -c user.email="$GIT_EMAIL" \
        commit -m "${MSG:-chore: snapshot $(date +%Y-%m-%dT%H:%M)}"
  else
    echo "Nothing to commit."
  fi
fi

# ---- robust local tip resolution -------------------------------------------
# In this sandbox the branch loose-ref gets reset to the packed base, but the
# reflog keeps the true tip (the reset bypasses the reflog). Use reflog -1 as
# the primary source, then fall back to the loose-ref file, then rev-parse.
resolve_local() {
  local b="$1" h
  h="$(git reflog -1 --format=%H "$b" 2>/dev/null)"
  if [[ -n "$h" ]] && git rev-parse -q --verify "$h" >/dev/null 2>&1; then
    echo "$h"; return
  fi
  if [[ -f ".git/refs/heads/$b" ]]; then
    h="$(cat ".git/refs/heads/$b" 2>/dev/null)"
    if [[ -n "$h" ]] && git rev-parse -q --verify "$h" >/dev/null 2>&1; then
      echo "$h"; return
    fi
  fi
  git rev-parse -q --verify "$b" 2>/dev/null
}
LOCAL="$(resolve_local "$BRANCH")"
if [[ -z "$LOCAL" ]]; then echo "ERROR: cannot resolve local tip for $BRANCH"; exit 1; fi
echo "Local  tip: $LOCAL ($BRANCH)"

# ---- authoritative remote tip -----------------------------------------------
git -c credential.helper=wincred fetch "$REMOTE" \
    "+refs/heads/$BRANCH:refs/remotes/$REMOTE/$BRANCH" 2>/dev/null || true
REMOTE_TIP="$(git rev-parse -q --verify "refs/remotes/$REMOTE/$BRANCH" 2>/dev/null || true)"
if [[ -z "$REMOTE_TIP" ]]; then
  REMOTE_TIP="$(git ls-remote "$REMOTE" "$BRANCH" 2>/dev/null | awk '{print $1}')"
fi
if [[ -z "$REMOTE_TIP" ]]; then
  echo "No remote tip; pushing $LOCAL as new branch."
  GIT_CONFIG_NOSYSTEM=1 git -c credential.helper=wincred push "$REMOTE" "$LOCAL:refs/heads/$BRANCH"
  exit $?
fi
echo "Remote tip: $REMOTE_TIP"

# ---- case A: force-push -----------------------------------------------------
if [[ $FORCE -eq 1 ]]; then
  echo "FORCE push $LOCAL -> $REMOTE/$BRANCH (rewrites remote history)"
  if GIT_CONFIG_NOSYSTEM=1 git -c credential.helper=wincred push --force \
        "$REMOTE" "$LOCAL:refs/heads/$BRANCH"; then
    write_refs "$LOCAL"; exit 0
  else exit 1; fi
fi

# ---- case B: local already contains remote (ahead or equal) -> FF push ------
if git merge-base --is-ancestor "$REMOTE_TIP" "$LOCAL" 2>/dev/null; then
  echo "Fast-forward push: $REMOTE_TIP..$LOCAL"
  if GIT_CONFIG_NOSYSTEM=1 git -c credential.helper=wincred push \
        "$REMOTE" "$LOCAL:refs/heads/$BRANCH"; then
    write_refs "$LOCAL"; exit 0
  else exit 1; fi
fi

# ---- case C: divergence -> rebase local onto remote via commit-tree ---------
# Builds new commits with the SAME trees/messages as local's commits, but with
# the remote tip as the new base. No porcelain rebase -> no CRLF/dirty-tree fail.
echo "Remote is ahead; rebasing local onto $REMOTE_TIP (commit-tree, no porcelain rebase)"
BASE="$(git merge-base "$LOCAL" "$REMOTE_TIP")"
COMMITS="$(git rev-list --reverse "$BASE..$LOCAL")"
PARENT="$REMOTE_TIP"
NEW="$REMOTE_TIP"
for c in $COMMITS; do
  TREE="$(git rev-parse "$c^{tree}")"
  MSG_C="$(git log -1 --format=%B "$c")"
  export GIT_AUTHOR_NAME="$(git log -1 --format=%an "$c")"
  export GIT_AUTHOR_EMAIL="$(git log -1 --format=%ae "$c")"
  export GIT_AUTHOR_DATE="$(git log -1 --format=%aI "$c")"
  export GIT_COMMITTER_NAME="$(git log -1 --format=%cn "$c")"
  export GIT_COMMITTER_EMAIL="$(git log -1 --format=%ce "$c")"
  export GIT_COMMITTER_DATE="$(git log -1 --format=%cI "$c")"
  NEW="$(printf '%s\n' "$MSG_C" | git commit-tree "$TREE" -p "$PARENT" -F -)"
  PARENT="$NEW"
done
echo "Rebased tip: $NEW"
if GIT_CONFIG_NOSYSTEM=1 git -c credential.helper=wincred push \
      "$REMOTE" "$NEW:refs/heads/$BRANCH"; then
  write_refs "$NEW"; exit 0
else exit 1; fi
