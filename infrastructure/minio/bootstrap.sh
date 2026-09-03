#!/bin/sh
set -eu

root_password="$(cat /run/secrets/minio_root_password)"
api_secret="$(cat /run/secrets/minio_api_secret_key)"
worker_secret="$(cat /run/secrets/minio_worker_secret_key)"

until mc alias set memoryos "${MINIO_ENDPOINT}" "${MINIO_ROOT_USER}" "${root_password}" >/dev/null 2>&1; do
  sleep 2
done

mc mb --ignore-existing "memoryos/${MINIO_BUCKET}"
mc anonymous set none "memoryos/${MINIO_BUCKET}"

cat > /tmp/api-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": ["arn:aws:s3:::${MINIO_BUCKET}/raw/*"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::${MINIO_BUCKET}/${MINIO_READINESS_KEY}"]
    }
  ]
}
EOF

cat > /tmp/worker-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:DeleteObject"],
      "Resource": ["arn:aws:s3:::${MINIO_BUCKET}/raw/*"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::${MINIO_BUCKET}/${MINIO_READINESS_KEY}"]
    }
  ]
}
EOF


mc admin user add memoryos "${MINIO_API_ACCESS_KEY}" "${api_secret}"
mc admin user add memoryos "${MINIO_WORKER_ACCESS_KEY}" "${worker_secret}"
mc admin policy create memoryos memoryos-api /tmp/api-policy.json
mc admin policy create memoryos memoryos-worker /tmp/worker-policy.json
mc admin policy attach memoryos memoryos-api --user "${MINIO_API_ACCESS_KEY}"
mc admin policy attach memoryos memoryos-worker --user "${MINIO_WORKER_ACCESS_KEY}"
printf 'memoryos-ready\n' | mc pipe --attr 'Content-Type=text/plain' "memoryos/${MINIO_BUCKET}/${MINIO_READINESS_KEY}"
mc alias set memoryos-api "${MINIO_ENDPOINT}" "${MINIO_API_ACCESS_KEY}" "${api_secret}" >/dev/null
mc cat "memoryos-api/${MINIO_BUCKET}/${MINIO_READINESS_KEY}" >/dev/null
mc alias set memoryos-worker "${MINIO_ENDPOINT}" "${MINIO_WORKER_ACCESS_KEY}" "${worker_secret}" >/dev/null
mc cat "memoryos-worker/${MINIO_BUCKET}/${MINIO_READINESS_KEY}" >/dev/null
