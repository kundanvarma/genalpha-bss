#!/usr/bin/env bash
# The box's own IAM role (#328): it may READ and DECRYPT one parameter — the
# Microsoft 365 mail app's credentials — and nothing else. Run ONCE, from a
# founder's laptop with the demo account's SSO profile; the box itself must not
# be able to create roles, so this cannot live in install.sh.
#
#   ops/cloud/aws-demo/box-role.sh            # show what would change (read-only)
#   ops/cloud/aws-demo/box-role.sh --apply    # create/refresh the role and attach it
#
# Idempotent: re-running refreshes the policy and re-attaches the profile.
set -euo pipefail
PROFILE=${AWS_PROFILE:-taranga-demo}
REGION=${AWS_REGION:-eu-north-1}
INSTANCE=${BOX_INSTANCE_ID:-i-016ec8c7f6bfbfb15}
PARAM=${MAIL_PARAMETER:-/taranga/demo/m365-mail}
ROLE=taranga-demo-box
APPLY=${1:-}
aws() { command aws --profile "$PROFILE" --region "$REGION" "$@"; }

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
PARAM_ARN="arn:aws:ssm:$REGION:$ACCOUNT:parameter${PARAM}"
POLICY=$(cat <<JSON
{"Version":"2012-10-17","Statement":[
 {"Sid":"ReadTheMailParameter","Effect":"Allow","Action":"ssm:GetParameter","Resource":"$PARAM_ARN"},
 {"Sid":"DecryptOnlyThroughSsm","Effect":"Allow","Action":"kms:Decrypt","Resource":"*",
  "Condition":{"StringEquals":{"kms:ViaService":"ssm.$REGION.amazonaws.com"},
               "StringLike":{"kms:EncryptionContext:PARAMETER_ARN":"$PARAM_ARN"}}}]}
JSON
)
TRUST='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

echo "[box-role] account $ACCOUNT, instance $INSTANCE, parameter $PARAM"
echo "[box-role] role $ROLE may: ssm:GetParameter on $PARAM_ARN; kms:Decrypt only via SSM for that parameter"
current=$(aws ec2 describe-iam-instance-profile-associations --filters Name=instance-id,Values="$INSTANCE" \
  --query 'IamInstanceProfileAssociations[0].IamInstanceProfile.Arn' --output text 2>/dev/null || echo None)
echo "[box-role] the instance's current profile: $current"
if [ "$APPLY" != "--apply" ]; then echo "[box-role] read-only: nothing changed (--apply to make it so)"; exit 0; fi

aws iam get-role --role-name "$ROLE" >/dev/null 2>&1 \
  || aws iam create-role --role-name "$ROLE" --assume-role-policy-document "$TRUST" \
       --description "Taranga demo box: read the M365 mail parameter, nothing else (#328)" >/dev/null
aws iam put-role-policy --role-name "$ROLE" --policy-name read-mail-parameter --policy-document "$POLICY"
aws iam get-instance-profile --instance-profile-name "$ROLE" >/dev/null 2>&1 \
  || { aws iam create-instance-profile --instance-profile-name "$ROLE" >/dev/null
       aws iam add-role-to-instance-profile --instance-profile-name "$ROLE" --role-name "$ROLE"; sleep 10; }
assoc=$(aws ec2 describe-iam-instance-profile-associations --filters Name=instance-id,Values="$INSTANCE" \
  --query 'IamInstanceProfileAssociations[0].AssociationId' --output text 2>/dev/null || echo None)
if [ "$assoc" = "None" ] || [ -z "$assoc" ]; then
  aws ec2 associate-iam-instance-profile --instance-id "$INSTANCE" --iam-instance-profile Name="$ROLE" >/dev/null
else
  aws ec2 replace-iam-instance-profile-association --association-id "$assoc" --iam-instance-profile Name="$ROLE" >/dev/null
fi
echo "[box-role] done: $INSTANCE runs as $ROLE"
