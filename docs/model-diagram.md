# PerfectPair Model Class Diagram

## perfectpair.model (Shared Models)

```mermaid
classDiagram
    class LoginRequest {
        +String username
        +String password
    }

    class LoginSuccess {
        +String accessToken
        +String refreshToken
    }

    class LoginFailure {
        +String reason
    }

    class RandomMessage {
        +String text
    }
```

## perfectpair.domain.model (Domain Models)

```mermaid
classDiagram
    %% Base Class
    class Auditable {
        +Option~OffsetDateTime~ createdAt
        +Option~OffsetDateTime~ updatedAt
    }

    %% Core Entities
    class Account {
        +ID id
        +String name
        +ID fundFlowId
        +AccountType accountType
        +Money balance
    }

    class Movement {
        +ID id
        +ID referenceId
        +ID outAccountId
        +ID inAccountId
        +Money amount
        +JSONBProps properties
        +Option~ID~ reconciliationId
        +MovementStatus status
    }

    class Reconciliation {
        +ID id
        +String description
    }

    class FundFlowConfig {
        +ID id
        +String name
        +String fundFlowJson
        +fundFlow() FundFlowSpec
    }

    class User {
        +Long id
        +Option~String~ firstName
        +String lastName
        +OffsetDateTime created
    }

    %% Enums
    class AccountType {
        <<enumeration>>
        Real
        Recon
    }

    class MovementStatus {
        <<enumeration>>
        Reconciled
        WithinSLA
        OutOfSLA
    }

    class Direction {
        <<enumeration>>
        IN
        OUT
    }

    %% Specification Classes
    class FundFlowSpec {
        +Seq~AccountSpec~ accountSpecs
        +Seq~MovementSpec~ movementSpecs
    }

    class MovementSpec {
        +String name
        +String inAccountName
        +String outAccountName
        +Array~String~ attributes
    }

    class AccountSpec {
        <<sealed trait>>
        +String name
        +Array~String~ properties
        +isReconSpec() Boolean
    }

    class ReconciliationAccountSpec {
        +String name
        +Matcher matcher
        +String agingLimit
    }

    class RealAccountSpec {
        +String name
    }

    %% Matcher Hierarchy
    class Matcher {
        <<sealed trait>>
    }

    class EqualityMatcher {
        +Array~String~ inKeys
        +Array~String~ outKeys
    }

    class SQLMatcher {
        +String sql
    }

    %% Helper Classes
    class JSONBProps {
        +Properties props
    }

    %% Error Hierarchy
    class ReconError {
        <<RuntimeException>>
    }

    class NonRetryableError {
    }

    class NotFoundError {
        +String msg
    }

    class NotFoundException {
        +String msg
    }

    class DBError {
    }

    %% Inheritance Relationships
    Auditable <|-- Account
    Auditable <|-- Movement
    Auditable <|-- Reconciliation
    Auditable <|-- FundFlowConfig
    Auditable <|-- User

    AccountSpec <|-- ReconciliationAccountSpec
    AccountSpec <|-- RealAccountSpec

    Matcher <|-- EqualityMatcher
    Matcher <|-- SQLMatcher

    ReconError <|-- NonRetryableError
    ReconError <|-- DBError
    NonRetryableError <|-- NotFoundError
    NonRetryableError <|-- NotFoundException

    %% Composition/Association Relationships
    Account --> AccountType : has
    Movement --> MovementStatus : has
    Movement --> JSONBProps : contains
    Movement --> Reconciliation : references

    FundFlowConfig --> FundFlowSpec : deserializes to
    FundFlowSpec --> AccountSpec : contains
    FundFlowSpec --> MovementSpec : contains

    ReconciliationAccountSpec --> Matcher : has

    Account --> FundFlowConfig : belongs to
    Movement --> Account : inAccountId
    Movement --> Account : outAccountId
```

## Type Aliases

| Alias | Type |
|-------|------|
| `ID` | `UUID` |
| `Money` | `BigDecimal` |
| `Properties` | `Map[String, String]` |
| `Tags` | `Map[String, String]` |

## Relationships Summary

- **Account** belongs to a **FundFlowConfig** via `fundFlowId`
- **Movement** references two **Accounts** (`inAccountId`, `outAccountId`)
- **Movement** optionally references a **Reconciliation** via `reconciliationId`
- **FundFlowConfig** contains serialized **FundFlowSpec** as JSON
- **FundFlowSpec** defines the structure with **AccountSpec** and **MovementSpec**
- All main entities extend **Auditable** for timestamp tracking