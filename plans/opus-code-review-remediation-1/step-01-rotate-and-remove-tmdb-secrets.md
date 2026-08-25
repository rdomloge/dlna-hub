# Step 01 — Remove committed TMDB credentials

**Phase:** 0 — Blockers
**Severity:** Critical (report: C1)
**Status:** Repo changes **done**. Rotation is **outstanding** and only the owner can do it.

---

## Outstanding — do this now

The credentials that were in `k8s/secret.yml` are in git history (since commit `aea885f`)
and cannot be un-leaked by editing files. **Rotate them:**

1. Go to <https://www.themoviedb.org/settings/api>.
2. Regenerate the API key **and** the read access token.
3. Put the new token in `k8s/secret.yml` (now gitignored — it will not be committed):

   ```bash
   $EDITOR k8s/secret.yml
   kubectl --kubeconfig 'C:\Users\Ramsay Domloge\k3s.yaml' apply -f k8s/secret.yml -n dlna-hub
   kubectl --kubeconfig 'C:\Users\Ramsay Domloge\k3s.yaml' rollout restart deployment/dlna-hub -n dlna-hub
   ```

4. Update the local IDE copy too — `.vscode/launch.json` has the old key and token in its
   `env` block. That file is gitignored, so it was never committed, but it still holds a
   compromised credential.

Until step 2 is done the old token is live and public.

---

## Done — repo changes already applied

- `k8s/secret.yml` untracked via `git rm --cached` (the local file is left in place so the
  existing deploy flow keeps working; it is now ignored).
- `k8s/secret.yml` added to `.gitignore` under a `## Secrets` heading.
- `k8s/secret.example.yml` added as the committed template, with `REPLACE_ME` placeholders
  and a note that only `TMDB_API_READ_ACCESS_TOKEN` is actually read by the backend.
- `README.md` deployment steps now say to copy the template rather than edit a tracked file;
  the secrets table and the manifest table were updated to match.

Verified: `git grep` finds no leaked value in any tracked file.

---

## Optional follow-up — purging git history

Rotation removes the risk; history rewriting removes the evidence. It is a separate,
disruptive operation and is **not** part of this plan:

```bash
# Requires git-filter-repo. Rewrites every commit; every clone must be re-cloned,
# and any open PR will need rebasing. Do not run this without deciding that first.
git filter-repo --path k8s/secret.yml --invert-paths
git push --force-with-lease --all
```

The repo has merged PRs (#1–#6), so the history is shared. Rotate first; decide on this
separately.

## Verify

```bash
git ls-files | grep -c "k8s/secret.yml"        # expect 0
git check-ignore -v k8s/secret.yml             # expect a .gitignore hit
git grep -I -l "REPLACE_ME" -- k8s             # expect k8s/secret.example.yml
```
