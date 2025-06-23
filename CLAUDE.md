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
- **Event Model**: `event-model` (meta-schema for describing system architecture and flows)
- **Example Service**: `example-service` (demonstrates CQRS/ES patterns with counter domain)

### Schema System

**Schemas are REQUIRED and central to the architecture** - they drive automatic validation throughout the entire system:

- **Required Schemas**: Every command, event, and query MUST have a corresponding schema defined
- **Schema-First Development**: The typical starting point for any project is defining all relevant schemas in `interface/schemas.clj`
- **Automatic Validation**: The `command-processor`, `event-store`, and `query-processor` automatically validate against schemas using the command/event/query name as the key
- **Centralized Registry**: Components define schemas using the `defschemas` macro from `schema-util`, which automatically registers them in a global mutable registry
- **Malli-Based**: Uses Malli for schema definition and validation with built-in time and core.async channel schemas
- **Event Model Meta-Schema**: The `event-model` component provides comprehensive schemas for describing entire system architectures, including commands, events, views, todo-processors, screens, and flows

### Projects

- `grain-core`: Main project with most components (alias: `:core`)
- `grain-event-store-postgres`: Specialized project for PostgreSQL event store functionality (alias: `:es-pg`)
- `grain-mulog-aws-cloudwatch-emf-publisher`: CloudWatch EMF publisher project (alias: `:mulog-aws-emf-pub`)
- `development`: Development environment project with `:dev` alias including example components

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
clojure -M:poly test project:grain-mulog-aws-cloudwatch-emf-publisher

# Run tests using project aliases
clojure -M:poly test project:core
clojure -M:poly test project:es-pg
clojure -M:poly test project:mulog-aws-emf-pub
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

# Create workspace build
clojure -M:poly create workspace
```

## Key Patterns

- All components expose their public API through `interface.clj` 
- Protocols are defined in `interface/protocols.clj` within components to avoid circular dependencies
- Event store supports PostgreSQL backend via separate `event-store-postgres` component
- Pub/sub uses core.async implementation (`pubsub/core/core_async.clj`)
- **Schema validation is mandatory** - every command, event, and query requires a schema definition
- **Service components MUST include schemas** in `interface/schemas.clj` defining all commands, events, and queries
- Service components organize domain logic into separate namespaces: `commands.clj`, `queries.clj`, `read-models.clj`, `todo-processors.clj`, `periodic-tasks.clj`
- **Event structure**: Events accessed from the event-store never have `:event/body` (unlike the event constructor function in the event-store interface which does include a body key)

## CQRS/Event Sourcing Architecture Patterns

### Service Component Structure (`example-service`)

Service components demonstrate the full CQRS/Event Sourcing pattern:

#### Command Handlers (`core/commands.clj`)
- Command functions take context with dependencies and return either:
  - Cognitect anomaly for validation errors
  - Map with `:command-result/events` containing events to persist
  - Optional `:command/result` for data returned to caller
- Commands validate business rules using read models before generating events
- Events use `uuid/+null+` as entity-id for single-aggregate applications
- Command registry maps command names to handler functions

#### Query Handlers (`core/queries.clj`)
- Query functions take context and return either:
  - Cognitect anomaly for not-found or validation errors  
  - Map with `:query/result` containing query response data
- Queries read from materialized read models, never directly from event store
- Query registry maps query names to handler functions

#### Read Models (`core/read-models.clj`)
- Event-driven projections built by reducing events from the event store
- Multimethods dispatch on `:event/name` to handle different event types
- `apply-events` function reduces events into materialized state
- Read models are rebuilt from scratch on each access (event replay)
- Tracing integration with mulog for observability

#### Todo Processors (`core/todo-processors.clj`)
- Event-driven background processors that subscribe to specific events
- Process one event at a time, receiving event in context
- Return `:result/events` with new events to persist
- Used for saga patterns, calculations, and event-driven workflows

#### Periodic Tasks (`core/periodic-tasks.clj`)
- Scheduled background tasks with flexible execution logic
- Receive context and time, no specific return value required
- Use context dependencies to perform work (commands, queries, etc.)

### Base Component Structure (`example-base`)

Bases wire together the entire application using Integrant for dependency injection:

#### System Configuration
- Integrant system map defines all components and their dependencies
- Context aggregates shared dependencies (event-store, registries, pubsub)
- Command and query registries are injected from service components
- Event pubsub configured with topic routing by event name

#### Lifecycle Management
- `ig/init-key` and `ig/halt-key!` methods manage component lifecycles
- Start/stop functions for entire application
- Shutdown hooks for graceful termination
- REPL-friendly development with atom-based state management

#### HTTP Integration
- Webserver with routes from command-request-handler and query-request-handler
- Health check endpoint for monitoring
- HTTP endpoints automatically handle command/query processing

#### Observability
- Mulog integration with console JSON publisher
- CloudWatch EMF publisher for metrics
- Tracing throughout the application
- Log sanitization for sensitive data

## System Architecture Modeling

### Event Model Component (`event-model`)

The `event-model` component provides a comprehensive meta-schema for describing and validating entire system architectures:

#### Naming Conventions
- **Commands**: Must use `:command/` namespace (e.g., `:command/create-user`)
- **Events**: Must use `:event/` namespace (e.g., `:event/user-created`)
- **Views**: Must use `:view/` namespace (e.g., `:view/user-list`)
- **Todo Processors**: Must use `:todo-processor/` namespace (e.g., `:todo-processor/send-welcome-email`)
- **Screens**: Must use `:screen/` namespace (e.g., `:screen/user-registration`)
- **Flows**: Must use `:flow/` namespace (e.g., `:flow/user-onboarding`)

#### System Flow Validation
- **Flow Steps**: Define valid transitions between system components
- **Valid Transitions**:
  - `view` → `todo-processor`, `screen`, or end
  - `todo-processor` → `command` or end
  - `screen` → `command` or end  
  - `command` → `event` or end
  - `event` → `view` or end
- **Flow Documentation**: Each flow includes description and ordered steps

#### Architecture Documentation
- **Commands**: Include description, schema, and optional given-when-then scenarios
- **Events**: Include description and schema
- **Views**: Include description and schema (read model projections)
- **Todo Processors**: Include description (background event handlers)
- **Screens**: Include description (UI components)

This meta-model enables:
- **Architecture Validation**: Ensure system flows follow valid patterns
- **Documentation Generation**: Auto-generate system documentation
- **Flow Analysis**: Understand system behavior and dependencies

## Development Workflow

### Schema-First Development Process

**Start every new feature by defining schemas first:**

1. **Define Schemas** in `interface/schemas.clj` using the `defschemas` macro:
   ```clojure
   (defschemas commands
     {:example/create-counter
      [:map
       [:name :string]]
      
      :example/increment-counter  
      [:map
       [:counter-id :uuid]]})
   
   (defschemas events
     {:example/counter-created
      [:map
       [:counter-id :uuid]
       [:name :string]]})
   
   (defschemas queries
     {:example/counters [:map]
      :example/counter
      [:map
       [:counter-id :uuid]]})
   ```

2. **Implement Handlers** - Command/query processors will automatically validate against these schemas by name
3. **Test Integration** - The system will reject invalid commands/queries/events at runtime

### REPL Development

The `development/src/example_app_demo.clj` file demonstrates the typical development workflow:

1. Start the service with `(service/start)` to get a running system
2. Extract the context and event-store for direct component interaction
3. Use REPL-driven development to test commands and queries directly:
   ```clojure
   ;; Process commands directly - automatically validated against schema
   (cp/process-command 
     (assoc context :command {:command/name :example/create-counter
                              :command/timestamp (time/now)
                              :command/id (random-uuid)
                              :name "Counter B"}))
   
   ;; Query the system - automatically validated against schema
   (qp/process-query
     (assoc context :query {:query/name :example/counters
                            :query/timestamp (time/now)
                            :query/id (random-uuid)}))
   ```
4. Alternatively, interact via HTTP endpoints on port 8080 using clj-http
5. Stop the service with `(service/stop service)` when done

### Application Structure
- **PostgreSQL Event Store**: Configured for localhost:5433 with database `obneyai`
- **HTTP Server**: Runs on port 8080 with command/query endpoints
- **nREPL Server**: Available on port 7888 for remote REPL access
- **Background Processing**: Todo processors and periodic tasks run automatically

## Testing

Tests are organized per component in standard Polylith structure. Use `clojure -M:poly test` to run all tests or target specific components/projects with `brick:` or `project:` selectors.