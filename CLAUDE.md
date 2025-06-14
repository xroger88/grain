# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Architecture

This is a Clojure Polylith workspace called "grain" organized as a hexagonal/component-based architecture. The workspace follows the Polylith architecture pattern with clear separation between components, bases, and projects.

### Key Components

The system is built around CQRS (Command Query Responsibility Segregation) and event sourcing patterns:

- **Command Processing**: `command-processor` (includes schemas), `command-request-handler`
- **Query Processing**: `query-processor`, `query-request-handler`, `query-schema`  
- **Event Management**: `event-store` (contains protocols and core logic), `event-store-postgres` (PostgreSQL implementation)
- **Task Management**: `todo-processor`
- **Infrastructure**: `webserver`, `pubsub`, `core-async-thread-pool`, `periodic-task`
- **Utilities**: `anomalies`, `time`, `schema-util`, `mulog-aws-cloudwatch-emf-publisher`

### Projects

- `grain-core`: Main project with most components
- `grain-event-store-postgres`: Specialized project for PostgreSQL event store functionality
- `development`: Development environment project with `:dev` alias

### Component Structure

Each component follows the standard Polylith pattern:
- `interface.clj`: Public API functions that delegate to `core.clj`
- `core.clj`: Implementation logic
- `interface/` subdirectory: Contains protocols, schemas, and other interface definitions
- Resources and tests in standard locations

### Protocol-Based Architecture

Key components use protocol-based abstraction for extensibility:
- **Event Store**: Protocol defined in `event-store/interface/protocols.clj`, implemented by `event-store-postgres`
- **Pub/Sub**: Protocol in `pubsub/core/protocol.clj` with core.async implementation
- Multi-methods used for type dispatch (e.g., event store creation by connection type)

## Common Commands

### Development
```bash
# Enter development REPL with all components available
clojure -M:dev

# View workspace information and health
clojure -M:poly info

# Check workspace validity
clojure -M:poly check

# Run all tests
clojure -M:poly test

# Run tests for specific component
clojure -M:poly test brick:component-name

# Run tests for specific project  
clojure -M:poly test project:grain-core
clojure -M:poly test project:grain-event-store-postgres
```

### Polylith Commands
```bash
# Create new component
clojure -M:poly create component name:my-component

# View component dependencies
clojure -M:poly deps

# View changed files since last stable point
clojure -M:poly diff

# Interactive shell
clojure -M:poly shell
```

## Key Patterns

- All components expose their public API through `interface.clj` 
- Protocols are defined in `interface/protocols.clj` within components to avoid circular dependencies
- Event store supports PostgreSQL backend via separate `event-store-postgres` component
- Pub/sub uses core.async implementation (`pubsub/core/core_async.clj`)
- Schema validation uses custom utilities and Malli
- Components include schemas in `interface/schemas.clj` when needed

## Testing

Tests are organized per component in standard Polylith structure. Use `clojure -M:poly test` to run all tests or target specific components/projects with `brick:` or `project:` selectors.