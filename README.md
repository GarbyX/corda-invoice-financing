# corda-invoice-financing
Corda 4 CorDapp for invoice financing: SME, buyer and financier settle atomically on a private ledger. Kotlin.


# Corda Invoice Financing

![Build](https://github.com/garbyx/corda-invoice-financing/actions/workflows/ci.yml/badge.svg)
![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Corda](https://img.shields.io/badge/Corda-4.x-red.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-JVM-purple.svg)

A Corda 4 CorDapp where an SME issues an invoice, the buyer accepts it, a financier advances funds and settlement happens atomically on a private ledger.

> **Disclaimer:** Illustrative project built on a fictional scenario. Not production-ready and not affiliated with any employer.

---

## The Problem

SMEs wait 30-90 days to be paid on invoices, which strains cash flow. Financiers want to lend against those invoices, but face fraud risk (duplicate financing of the same invoice) and slow, paper-heavy verification.

## The Solution

A shared, tamper-proof ledger where:
- Each invoice exists as a single, unique state, so it can not be financed twice
- Only the parties involved see the data (Corda's point-to-point privacy)
- Acceptance, financing and settlement are enforced by contract code

## Architecture

![Architecture diagram](docs/images/architecture.png)

| Actor | Role |
|-------|------|
| **SME** | Issues the invoice |
| **Buyer** | Accepts the invoice and pays at maturity |
| **Financier** | Offers an advance against the accepted invoice |
| **Notary** | Prevents double-spends and notarises transactions |

**Lifecycle:** `ISSUED` -> `ACCEPTED` -> `FINANCED` -> `SETTLED`

## Features

- **States:** `InvoiceState`, `FinanceOfferState`
- **Contracts:** buyer must sign acceptance; financed amount cannot exceed face value; only the current holder can settle
- **Flows:** `IssueInvoiceFlow`, `AcceptInvoiceFlow`, `OfferFinancingFlow`, `SettleInvoiceFlow`
- **Tests:** contract tests and flow tests using `MockNetwork`

## Prerequisites

- JDK 8 (Oracle)
- IntelliJ IDEA (Community Edition is sufficient)
- Git

## Quick Start

```bash
git clone https://github.com/garby/corda-invoice-financing.git
cd corda-invoice-financing

# Run tests
./gradlew test

# Build the nodes
./gradlew deployNodes

# Start all nodes
Linux/macOS: build/nodes/runnodes
Windows: build\nodes\runnodes.bat
```

## Demo

![Demo](docs/images/demo.gif)

Run the flow end to end from the SME node shell (parameters are placeholders; match them to your flow signatures):

```
flow start IssueInvoiceFlow buyer: "O=Buyer,L=Lagos,C=NG", amount: 1000000, dueDate: "2026-12-31"
flow start AcceptInvoiceFlow invoiceId: <id>
```

Then, from the Financier node shell:

```
flow start OfferFinancingFlow invoiceId: <id>, advanceRate: 0.85
```

Check state at any time with:

```
run vaultQuery contractStateType: com.example.states.InvoiceState
```

## Project Structure

```
.
├── contracts/   # States and contract verification logic
├── workflows/   # Flows
├── docs/images/ # Diagrams, screenshots, demo GIF
└── build.gradle
```

## Roadmap

- [ ] Oracle for due-date and FX checks
- [ ] REST API layer (Spring Boot client)
- [ ] Partial financing and multiple financiers
- [ ] Migration notes for Corda 5 (cloud)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues and PRs are welcome.

## License

Apache 2.0. See [LICENSE](LICENSE).

## Author

**Gabriel Madichie**: 
Corda Certified Blockchain Developer.
[LinkedIn](https://www.linkedin.com/in/gabriel13/)




