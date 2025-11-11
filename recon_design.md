# Recon Design

## Accounts

- Clearing Account
  - Send Clearing 100 

- Clearing Reconciliation Account
  - Send Clearing -100 ID:123
  - BAI Received 100 ID: 123

- BAI Received Account
  - BAI Received -100

    
## Movement Spec

Send Clearing 
IN: Clearing
OUT: Clearing UnReconciled
TAG: ID:123

Receive BAI File
IN: Clearing UnReconciled 
OUT: BAI Received
TAG: ID:123

[//]: # (## Reconciliation)

[//]: # (MovementID: 1)

[//]: # (MovementID: 2)

[//]: # (Status Reconciled)

