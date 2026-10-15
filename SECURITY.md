# Security boundary

RelayGuard is a local portfolio simulator. It has unauthenticated demo, dashboard and replay endpoints. It defaults to loopback access and uses only synthetic data. It must not be deployed to an internet-facing host as-is.

Do not submit real bank, card, borrower, customer, employer, or provider-secret data. The default demonstration signing key is deliberately public and is not a production credential.

If you find a flaw, use a private channel through the author's [Upwork profile](https://www.upwork.com/freelancers/~015af8c955239d96f0) rather than posting credentials or personal data in a public issue.
