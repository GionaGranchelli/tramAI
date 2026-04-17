# Aurora Documentation

This directory is the base documentation set for Aurora, derived from the current project plan in [PLAN.md](/home/gionag/Development/aurora/PLAN.md) and the product and architecture direction in [DESIGN.md](/home/gionag/Development/aurora/DESIGN.md).

## Structure

- `adr/`: architecture decision records for the major technical choices already established in the design
- `architecture/`: high-level system architecture and module layout
- `reference/`: placeholders for configuration and provider reference material
- `guides/`: placeholders for user-facing how-to documentation

## Suggested Reading Order

1. [Architecture Overview](./architecture/overview.md)
2. [Module Overview](./architecture/modules.md)
3. [Roadmap Summary](./roadmap.md)
4. [ADR Index](./adr/README.md)

## Documentation Scope

This initial scaffold focuses on:

- the project's intent and architectural direction
- the module and integration boundaries
- the main decisions that deserve ADRs now

It does not try to fully document runtime APIs that do not exist yet. Those belong in later milestone documentation and KDoc once implementation lands.
