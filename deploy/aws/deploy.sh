#!/usr/bin/env bash
# Runs on YOUR machine. Needs AWS CLI v2 + the Session Manager plugin, and credentials for the
# target account (AWS_PROFILE / AWS_REGION are respected).
#
#   deploy.sh secrets   store settings + secrets in SSM Parameter Store (prompts; nothing is echoed)
#   deploy.sh up        create or update the CloudFormation stack (first boot builds the app)
#   deploy.sh pair      link WhatsApp: shows the wacli QR code, scan it with your phone
#   deploy.sh update    pull the latest code from the repo branch, rebuild, restart
#   deploy.sh restart   restart the app
#   deploy.sh logs      follow the app log
#   deploy.sh shell     shell on the instance (via SSM, no SSH/ports)
#   deploy.sh stop|start  stop/start the instance now (a daily schedule also does this; stopped = only the disk is billed)
#   deploy.sh down      delete the stack and its parameters
set -euo pipefail

STACK=${JAVACLAW_STACK:-javaclaw}
PREFIX=${JAVACLAW_PARAM_PREFIX:-/javaclaw}
export AWS_REGION=${AWS_REGION:-${AWS_DEFAULT_REGION:-ap-south-1}}
HERE=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$HERE/../.." && pwd)
KEYCHAIN_SERVICE=javaclaw-google-api-key

instance_id() {
    aws cloudformation describe-stacks --stack-name "$STACK" \
        --query "Stacks[0].Outputs[?OutputKey=='InstanceId'].OutputValue" --output text
}

put_secret() {  # name value
    aws ssm put-parameter --name "$PREFIX/$1" --type SecureString --overwrite --value "$2" >/dev/null
}

stack_exists() {
    aws cloudformation describe-stacks --stack-name "$STACK" >/dev/null 2>&1
}

# A persistent Spot request relaunches its instance when the instance is terminated. Cancel it
# before CloudFormation terminates the instance (replacement or delete); a running instance keeps running.
cancel_spot_request() {
    local id req
    id=$(instance_id 2>/dev/null) || return 0
    { [ -n "$id" ] && [ "$id" != None ]; } || return 0
    req=$(aws ec2 describe-instances --instance-ids "$id" \
        --query 'Reservations[0].Instances[0].SpotInstanceRequestId' --output text)
    { [ -n "$req" ] && [ "$req" != None ]; } || return 0
    aws ec2 cancel-spot-instance-requests --spot-instance-request-ids "$req" >/dev/null
    echo "cancelled Spot request $req (its instance is about to be terminated)"
}

run_remote() {  # interactive command on the instance
    aws ssm start-session --target "$(instance_id)" \
        --document-name AWS-StartInteractiveCommand --parameters "command=[\"$1\"]"
}

cmd=${1:-}; [ $# -gt 0 ] && shift

case "$cmd" in
    secrets)
        # Gemini key: from the macOS Keychain if stored there, otherwise a hidden prompt.
        key=$(security find-generic-password -s "$KEYCHAIN_SERVICE" -w 2>/dev/null || true)
        if [ -z "$key" ]; then read -rsp "Google AI Studio API key (hidden): " key; echo; fi
        put_secret google-api-key "$key"; unset key
        echo "stored $PREFIX/google-api-key"

        read -rsp "Cloudflare tunnel token (hidden, Enter to skip): " token; echo
        if [ -n "$token" ]; then put_secret cloudflare-tunnel-token "$token"; echo "stored $PREFIX/cloudflare-tunnel-token"; fi
        unset token

        read -rp "WhatsApp chat JID to answer in (e.g. 91XXXXXXXXXX@s.whatsapp.net, Enter to skip): " jid
        [ -n "$jid" ] && aws ssm put-parameter --name "$PREFIX/whatsapp-chat-jid" --type String --overwrite --value "$jid" >/dev/null
        read -rp "Gemini model [gemini-3.5-flash-lite]: " model
        aws ssm put-parameter --name "$PREFIX/gemini-model" --type String --overwrite --value "${model:-gemini-3.5-flash-lite}" >/dev/null
        read -rp "Gemini model for WhatsApp group digests [gemini-3.1-flash-lite]: " model
        aws ssm put-parameter --name "$PREFIX/gemini-digest-model" --type String --overwrite --value "${model:-gemini-3.1-flash-lite}" >/dev/null

        if [ -f "$REPO_ROOT/workspace/AGENT.private.md" ]; then
            read -rp "Upload workspace/AGENT.private.md (your personal system prompt)? [y/N] " yn
            if [ "$yn" = y ] || [ "$yn" = Y ]; then
                aws ssm put-parameter --name "$PREFIX/agent-md" --type SecureString --overwrite \
                    --value "file://$REPO_ROOT/workspace/AGENT.private.md" >/dev/null
                echo "stored $PREFIX/agent-md"
            fi
        fi
        ;;
    up)
        existed=false; stack_exists && existed=true
        out=$(aws cloudformation deploy --stack-name "$STACK" \
            --template-file "$HERE/javaclaw.yaml" \
            --capabilities CAPABILITY_IAM \
            --parameter-overrides "ParameterPrefix=$PREFIX" "$@" \
            --tags Project=javaclaw \
            --no-execute-changeset --no-fail-on-empty-changeset) || { echo "$out"; exit 1; }
        change_set=$(grep -oE 'arn:aws[^ ]*:changeSet/[^ ]+' <<<"$out" | head -1 || true)
        if [ -z "$change_set" ]; then echo "no changes"; exit 0; fi
        if $existed; then
            replace=$(aws cloudformation describe-change-set --change-set-name "$change_set" \
                --query "Changes[?ResourceChange.LogicalResourceId=='Instance'].ResourceChange.Replacement" --output text)
            case "$replace" in True|Conditional)
                echo "this update replaces the instance: its disk (WhatsApp session, workspace) is lost; pair again afterwards"
                read -rp "Continue? [y/N] " yn; [ "$yn" = y ] || [ "$yn" = Y ] || { echo "aborted"; exit 1; }
                cancel_spot_request ;;
            esac
        fi
        aws cloudformation execute-change-set --change-set-name "$change_set"
        if $existed; then aws cloudformation wait stack-update-complete --stack-name "$STACK"
        else aws cloudformation wait stack-create-complete --stack-name "$STACK"; fi
        if $existed; then echo "stack updated; instance $(instance_id) in $AWS_REGION"
        else
            echo "instance $(instance_id) in $AWS_REGION; first boot installs and builds (~10-15 min)."
            echo "watch it with: $0 shell, then: sudo tail -f /var/log/javaclaw-bootstrap.log"
        fi
        ;;
    pair)    run_remote "sudo -iu javaclaw wacli auth" ;;
    restart) run_remote "sudo systemctl restart javaclaw && sudo systemctl status javaclaw --no-pager" ;;
    logs)    run_remote "sudo journalctl -u javaclaw -f -n 100" ;;
    shell)   aws ssm start-session --target "$(instance_id)" ;;
    update)  run_remote "sudo bash /opt/javaclaw/app/deploy/aws/bootstrap.sh update" ;;
    stop)    aws ec2 stop-instances --instance-ids "$(instance_id)" --output text ;;
    start)   aws ec2 start-instances --instance-ids "$(instance_id)" --output text ;;
    down)
        read -rp "Delete stack '$STACK' (instance, disk with WhatsApp session + workspace, VPC) and $PREFIX/* parameters? Type the stack name: " answer
        [ "$answer" = "$STACK" ] || { echo "aborted"; exit 1; }
        cancel_spot_request
        aws cloudformation delete-stack --stack-name "$STACK"
        aws cloudformation wait stack-delete-complete --stack-name "$STACK"
        names=$(aws ssm get-parameters-by-path --path "$PREFIX" --query 'Parameters[].Name' --output text)
        [ -n "$names" ] && aws ssm delete-parameters --names $names >/dev/null
        echo "deleted"
        ;;
    *)
        sed -n '2,15p' "$0"; exit 2 ;;
esac
