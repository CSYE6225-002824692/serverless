# GCP Serverless Email Verification Function

This repository contains a Google Cloud Platform (GCP) serverless function designed for email verification processes. The function listens to Pub/Sub events, decodes the messages, and sends email verifications using Mailgun API. Additionally, it logs email verification details into a MySQL database using Google Cloud SQL and fetches email templates from Google Cloud Storage.

## Features

- **Email Sending**: Utilizes Mailgun API for sending out verification emails.
- **Database Logging**: Records verification details in a Cloud SQL MySQL database.
- **Dynamic Email Templates**: Fetches email templates from Google Cloud Storage, allowing for easy updates and customization.

## Requirements

- Google Cloud Pub/Sub
- Google Cloud SQL (MySQL)
- Google Cloud Storage
- Mailgun Account

## Environment Variables

To run this function, you will need to set the following environment variables:

- `MAILGUN_DOMAIN`: Your Mailgun domain.
- `MAILGUN_DOMAIN_API_KEY`: Your Mailgun API key.
- `INSTANCE_CONNECTION_NAME`: Your Cloud SQL instance connection name.
- `DB_NAME`: The database name in your Cloud SQL instance.
- `DB_USERNAME`: The database username.
- `DB_PASSWORD`: The database password.
- `GCS_BUCKET_NAME`: The name of your Google Cloud Storage bucket.
- `EMAIL_TEMPLATE_FILENAME`: The filename of your email template stored in GCS.
- `FROM_NAME`: The sender's name that appears in the email.
- `FROM_EMAIL`: The sender's email address.
- `VERIFICATION_LINK`: The base URL to which the verification token is appended to create the verification link.

## Deployment

This function is designed to be deployed on Google Cloud Functions.
