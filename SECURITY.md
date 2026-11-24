# Security policy

Please report security issues **privately** through *Security → Report a vulnerability* on this repository, not in a public issue.

Areas we care about most:

- `ApprovalGate`: an external action must not run without a valid one-time token bound to its exact arguments
- secret handling: API keys and tokens are never logged or committed, and in the app they are encrypted with Android Keystore
- packaging: the on-device APK forge must never include keystores, `.env` files or keys
