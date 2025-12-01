use authz::Role;
use axum::Extension;
use axum::Json;
use axum::body::Bytes;
use axum::extract::Path;
use axum::extract::State;
use axum::http::StatusCode;
use axum::http::header::CACHE_CONTROL;
use axum::http::header::CONTENT_TYPE;
use axum::response::IntoResponse;
use editoast_derive::EditoastError;
use serde::Serialize;
use std::sync::Arc;
use thiserror::Error;
use utoipa::ToSchema;

use crate::error::Result;
use database::DbConnectionPoolV2;
use editoast_models::Document;
use editoast_models::prelude::*;

use super::AuthenticationExt;
use super::AuthorizationError;

#[derive(Error, Debug, EditoastError)]
#[editoast_error(base_id = "document")]
pub(in crate::views) enum DocumentErrors {
    #[error("Document '{document_key}' not found")]
    #[editoast_error(status = 404)]
    NotFound { document_key: i64 },
    #[error(transparent)]
    #[editoast_error(status = 500)]
    Database(#[from] editoast_models::Error),
    #[error(transparent)]
    #[editoast_error(status = 503)]
    DatabaseUnavailable(#[from] database::DatabasePoolError),
    #[error(transparent)]
    #[editoast_error(status = 403)]
    Authorization(#[from] AuthorizationError),
}

impl crate::views::error::ViewError for DocumentErrors {
    const LABEL: &'static str = "DocumentsErrors";

    fn responses() -> Vec<super::error::OpenApiResponse> {
        Vec::from([
            super::error::OpenApiResponse {
                variant: Some("NotFound"),
                message_template: Some("Document '{document_key}' not found"),
                status: axum::http::StatusCode::NOT_FOUND,
                context: Vec::from([super::error::ContextEntry {
                    key: "document_key",
                    schema: <i64 as utoipa::PartialSchema>::schema(),
                }]),
            },
            super::error::OpenApiResponse {
                variant: Some("Database"),
                status: axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                message_template: None,
                context: Vec::from([]),
            },
            super::error::OpenApiResponse {
                variant: Some("DatabaseUnavailable"),
                status: axum::http::StatusCode::INTERNAL_SERVER_ERROR,
                message_template: None,
                context: Vec::from([]),
            },
            super::error::OpenApiResponse {
                variant: Some("Authorization"),
                message_template: None,
                status: axum::http::StatusCode::FORBIDDEN,
                context: Vec::from([]),
            },
        ])
    }

    fn status(&self) -> axum::http::StatusCode {
        match self {
            Self::NotFound { .. } => axum::http::StatusCode::NOT_FOUND,
            Self::Database(_) => axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            Self::DatabaseUnavailable { .. } => axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            Self::Authorization(_) => axum::http::StatusCode::FORBIDDEN,
        }
    }

    fn context(self) -> std::collections::HashMap<String, serde_json::Value> {
        Default::default()
    }

    fn variant_label(&self) -> Option<&'static str> {
        match self {
            Self::NotFound { .. } => Some("NotFound"),
            Self::Database(_) => Some("DatabaseError"),
            Self::DatabaseUnavailable { .. } => Some("DatabaseUnavailable"),
            Self::Authorization(_) => Some("AuthorizationError"),
        }
    }
}

impl utoipa::IntoResponses for DocumentErrors {
    fn responses() -> std::collections::BTreeMap<
        String,
        utoipa::openapi::RefOr<utoipa::openapi::response::Response>,
    > {
        <Self as super::error::ViewError>::utoipa_responses().into()
    }
}

impl axum::response::IntoResponse for DocumentErrors {
    fn into_response(self) -> axum::response::Response {
        <Self as super::error::ViewError>::into_response(self)
    }
}

/// Returns a document of any type
#[editoast_derive::route]
#[utoipa::path(
    get, path = "",
    tag = "documents",
    params(
        ("document_key" = i64, Path, description = "The document's key"),
    ),
    responses(
        (
            status = 200,
            description = "Document content",
            content_type = "application/octet-stream",
            body = String,
        ),
    )
)]
pub(in crate::views) async fn get(
    State(db_pool): State<Arc<DbConnectionPoolV2>>,
    Extension(auth): AuthenticationExt,
    Path(document_id): Path<i64>,
) -> Result<impl IntoResponse> {
    let authorized = auth
        .check_roles([Role::OperationalStudies, Role::Stdcm].into())
        .await
        .map_err(AuthorizationError::AuthError)?;
    if !authorized {
        return Err(AuthorizationError::Forbidden.into());
    }
    let conn = &mut db_pool.get().await?;
    let doc = Document::retrieve_or_fail(conn.clone(), document_id, || DocumentErrors::NotFound {
        document_key: document_id,
    })
    .await?;
    Ok((
        StatusCode::OK,
        [
            (CONTENT_TYPE, doc.content_type),
            (CACHE_CONTROL, "public, max-age=3600".to_string()),
        ],
        doc.data,
    ))
}

#[derive(Serialize, ToSchema)]
struct NewDocumentResponse {
    document_key: i64,
}

/// Post a new document (content_type by header + binary data)
#[editoast_derive::route]
#[utoipa::path(
    post, path = "",
    tag = "documents",
    params(
        ("content_type" = String, Header, description = "The document's content type"),
    ),
    request_body(content_type = "application/octet-stream", content = String),
    responses(
        (status = 201, description = "The document was created", body = NewDocumentResponse),
    )
)]
pub(in crate::views) async fn post(
    State(db_pool): State<Arc<DbConnectionPoolV2>>,
    Extension(auth): AuthenticationExt,
    axum_extra::TypedHeader(content_type): axum_extra::TypedHeader<headers::ContentType>,
    bytes: Bytes,
) -> Result<impl IntoResponse> {
    let authorized = auth
        .check_roles([Role::OperationalStudies].into())
        .await
        .map_err(AuthorizationError::AuthError)?;
    if !authorized {
        return Err(AuthorizationError::Forbidden.into());
    }
    let content_type = content_type.to_string();

    // Create document
    let conn = &mut db_pool.get().await?;
    let doc = Document::changeset()
        .content_type(content_type.to_owned())
        .data(bytes.to_vec())
        .create(conn)
        .await?;

    // Response
    Ok((
        StatusCode::CREATED,
        Json(NewDocumentResponse {
            document_key: doc.id,
        }),
    ))
}

/// Delete an existing document
#[editoast_derive::route]
#[utoipa::path(
    delete, path = "",
    tag = "documents",
    params(
        ("document_key" = i64, Path, description = "The document's key"),
    ),
    responses(
        (status = 204, description = "The document was deleted"),
        DocumentErrors,
    )
)]
pub(in crate::views) async fn delete(
    State(db_pool): State<Arc<DbConnectionPoolV2>>,
    Extension(auth): AuthenticationExt,
    Path(document_id): Path<i64>,
) -> Result<impl IntoResponse, DocumentErrors> {
    let authorized = auth
        .check_roles([Role::OperationalStudies].into())
        .await
        .map_err(AuthorizationError::AuthError)?;
    if !authorized {
        return Err(AuthorizationError::Forbidden.into());
    }
    let conn = &mut db_pool.get().await?;
    Document::delete_static_or_fail(conn, document_id, || DocumentErrors::NotFound {
        document_key: document_id,
    })
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
mod tests {
    use axum::http::StatusCode;
    use axum::http::header;

    use serde::Deserialize;

    use super::*;
    use crate::views::test_app::TestAppBuilder;

    #[derive(Deserialize, Clone, Debug)]
    struct PostDocumentResponse {
        document_key: i64,
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 1)]
    async fn document_post() {
        let app = TestAppBuilder::default_app();
        let pool = app.db_pool();

        let request = app
            .post("/documents")
            .add_header(
                header::CONTENT_TYPE,
                header::HeaderValue::from_str("text/plain").unwrap(),
            )
            .bytes("Document post test data".into());

        // Insert document
        let response = request.await;
        response.assert_status(StatusCode::CREATED);
        let new_doc = response.json::<PostDocumentResponse>().document_key;

        // Get create document
        let document = Document::retrieve(pool.get_ok(), new_doc)
            .await
            .expect("Failed to retrieve document")
            .expect("Document not found");

        assert_eq!(document.data, b"Document post test data".to_vec());
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 1)]
    async fn get_document() {
        let app = TestAppBuilder::default_app();
        let pool = app.db_pool();

        // Insert document test
        let document = Document::changeset()
            .data(b"Document post test data".to_vec())
            .content_type(String::from("text/plain"))
            .create(&mut pool.get_ok())
            .await
            .expect("Failed to create document");

        // Get document test
        let response = app.get(&format!("/documents/{}", document.id)).await;
        response.assert_status(StatusCode::OK);
        let response = response.as_bytes();

        assert_eq!(response.as_ref(), b"Document post test data");
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 1)]
    async fn document_delete() {
        let app = TestAppBuilder::default_app();
        let pool = app.db_pool();

        // Insert document test
        let document = Document::changeset()
            .data(b"Document post test data".to_vec())
            .content_type(String::from("text/plain"))
            .create(&mut pool.get_ok())
            .await
            .expect("Failed to create document");

        // Delete document request
        let response = app
            .delete(format!("/documents/{}", document.id).as_str())
            .await;
        response.assert_status(StatusCode::NO_CONTENT);

        // Get create document
        let document = Document::exists(&mut pool.get_ok(), document.id)
            .await
            .expect("Failed to retrieve document");

        assert!(!document);
    }
}
