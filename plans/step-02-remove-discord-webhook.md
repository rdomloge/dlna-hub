# Step 02 — Remove the Discord webhook from AGENTS.md

**Phase:** 0 — Blockers
**Severity:** Critical (report: C1)
**Status:** Repo changes **done**. Rotation is **outstanding** and only the owner can do it.

---

## Outstanding — do this now

The webhook URL is in git history and cannot be un-leaked by editing the file. Anyone who
has it can post arbitrary messages into that Discord channel.

1. In Discord: **Server Settings → Integrations → Webhooks**, delete the old webhook.
2. Create a new one and copy its URL.
3. Export it in your shell profile so the notification commands in `AGENTS.md` keep working:

   ```powershell
   # PowerShell profile
   $env:DISCORD_WEBHOOK_URL = "https://discord.com/api/webhooks/..."
   ```

   ```bash
   # bash profile
   export DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/..."
   ```

---

## Done — repo changes already applied

`AGENTS.md` no longer contains the URL. Both notification examples now read it from
`DISCORD_WEBHOOK_URL`:

```
curl.exe -s -X POST "$DISCORD_WEBHOOK_URL" -H "Content-Type: application/json" -d "{\"content\":\"<short summary>\"}"
```

```
node -e "fetch(process.env.DISCORD_WEBHOOK_URL,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({content:'<short summary>'})}).then(r=>console.log(r.status))"
```

The surrounding guidance was kept — the Schannel TLS fallback note is genuinely useful, and
the "a 4xx means the webhook is invalid, do not retry in a loop" rule now also covers the
case where `DISCORD_WEBHOOK_URL` is unset.

## Verify

```bash
grep -c "discord.com/api/webhooks/" AGENTS.md   # expect 0
grep -c "DISCORD_WEBHOOK_URL" AGENTS.md         # expect 3 or more
```
