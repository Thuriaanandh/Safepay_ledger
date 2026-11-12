# SafePay Ledger - Terraform Infrastructure as Code (LocalStack)

# 1. DynamoDB Fast-Path Idempotency Key Store
resource "aws_dynamodb_table" "idempotency_store" {
  name         = var.idempotency_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "idempotency_key"

  attribute {
    name = "idempotency_key"
    type = "S"
  }

  ttl {
    attribute_name = "expires_at"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  tags = {
    Name        = "SafePay-Idempotency-Keys"
    Service     = "payment-api"
    SecurityTier = "PCI-DSS-Tier-1"
  }
}

# 2. Least-Privilege IAM Policy for payment-api
# Demonstrates zero-trust principle: payment-api can only read and conditionally write idempotency keys
resource "aws_iam_policy" "payment_api_dynamodb" {
  name        = "safepay-payment-api-dynamodb-policy"
  description = "Least privilege IAM policy granting payment-api access to atomic conditional idempotency keys"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowIdempotencyOperations"
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DescribeTable"
        ]
        Resource = aws_dynamodb_table.idempotency_store.arn
      }
    ]
  })
}

# 3. Service Execution Role
resource "aws_iam_role" "payment_api_role" {
  name = "safepay-payment-api-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "payment_api_attach" {
  role       = aws_iam_role.payment_api_role.name
  policy_arn = aws_iam_policy.payment_api_dynamodb.arn
}
