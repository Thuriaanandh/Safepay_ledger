output "dynamodb_table_name" {
  description = "DynamoDB Idempotency Store Table Name"
  value       = aws_dynamodb_table.idempotency_store.name
}

output "dynamodb_table_arn" {
  description = "DynamoDB Idempotency Store Table ARN"
  value       = aws_dynamodb_table.idempotency_store.arn
}

output "iam_policy_arn" {
  description = "ARN of the least-privilege IAM policy"
  value       = aws_iam_policy.payment_api_dynamodb.arn
}

output "iam_role_arn" {
  description = "ARN of the payment-api IAM execution role"
  value       = aws_iam_role.payment_api_role.arn
}
