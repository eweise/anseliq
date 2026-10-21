# Installing AnselIQ on AWS EKS

This overlay is a template for one customer install. Copy the folder for the customer, fill in every
`REPLACE_ME_` value, and apply it. `grep -rn REPLACE_ME .` lists what's left to fill in.

## What the cluster and account need

| Placeholder | What it is |
|---|---|
| `REPLACE_ME_REGISTRY` | Where the cluster pulls the AnselIQ images from, e.g. an ECR repository in the customer's account (`<account>.dkr.ecr.<region>.amazonaws.com`). Tags are set in `kustomization.yaml`. |
| `REPLACE_ME_RDS_ENDPOINT` | A PostgreSQL instance (RDS or Aurora) the cluster can reach. See Database below. |
| `REPLACE_ME_AWS_REGION` | The region of the S3 buckets the file feeds read. |
| `REPLACE_ME_AWS_ACCOUNT_ID`, `REPLACE_ME_S3_READ_ROLE` | The IAM role the backend pods read S3 with. See S3 below. |
| `REPLACE_ME_ACM_CERTIFICATE_ARN` | An ACM certificate for the hostname users will open AnselIQ at. |

The cluster also needs the [AWS Load Balancer Controller](https://kubernetes-sigs.github.io/aws-load-balancer-controller/),
which creates the ALB from the Ingress. The ALB is `internal` (reachable from the customer's network
only); change `scheme` in `ingress-alb.yaml` to `internet-facing` if users reach it over the internet.
Point the hostname's DNS at the ALB once it exists (`kubectl get ingress -n anseliq`).

### Database

Create an empty database (`recon_db` unless `DB_NAME` is changed) and a user that owns it. The
backend's migrations create the `recon` schema and everything in it on first start, so nothing else
needs running by hand:

```sql
CREATE USER anseliq WITH PASSWORD '...';
CREATE DATABASE recon_db OWNER anseliq;
```

Each backend pod holds up to `DB_MAX_CONNECTIONS` (10) pooled connections plus 2; keep the total
across pods under the instance's `max_connections`.

### S3

An IAM role that can read the feed buckets, which only the `anseliq-backend` service account can
assume (IRSA). Its permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::<feed-bucket>" },
    { "Effect": "Allow", "Action": "s3:GetObject", "Resource": "arn:aws:s3:::<feed-bucket>/*" }
  ]
}
```

and its trust policy, with the cluster's OIDC provider:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<account>:oidc-provider/<oidc-provider>" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "<oidc-provider>:sub": "system:serviceaccount:anseliq:anseliq-backend" }
    }
  }]
}
```

## Installing

```bash
cp secrets.env.example secrets.env   # then fill it in
kubectl apply -k .
```

Then sign in as `ADMIN_EMAIL`, which the backend creates on its first start.

An upgrade is the same `kubectl apply -k .` with new image tags: each backend pod migrates the
database before it starts, and running jobs drain before old pods stop.
