use darling::FromDeriveInput;
use darling::FromField;
use darling::FromMeta;
use darling::FromVariant;
use darling::Result;
use darling::ast;
use proc_macro2::TokenStream;
use quote::ToTokens;
use quote::quote;
use syn::DeriveInput;
use syn::spanned::Spanned;

#[derive(Debug, FromDeriveInput)]
#[darling(
    attributes(view_error),
    forward_attrs(allow, doc, cfg, from, error),
    supports(
        struct_newtype,
        struct_named,
        struct_tuple,
        struct_unit,
        enum_named,
        enum_tuple,
        enum_unit,
    )
)]
struct Args {
    ident: syn::Ident,
    data: ast::Data<VariantArgs, FieldArgs>,

    /// Changes the base name of the error label
    #[darling(default)]
    name: Option<String>,

    /// Whether to include type data into the error `context` field
    ///
    /// Default: false
    /// Incompatible with `context_with`.
    #[darling(default)]
    context: bool,

    #[darling(flatten)]
    args: StructArgs,
}

#[derive(Debug, FromVariant)]
#[darling(attributes(view_error), forward_attrs(allow, doc, cfg, from, error))]
struct VariantArgs {
    ident: syn::Ident,
    fields: ast::Fields<FieldArgs>,
    // TODO: use StatusCode instead
    #[darling(default)]
    status: StatusCodeArg,
    #[darling(default)]
    context: bool,
}

#[derive(Debug, Clone, FromMeta)]
struct StatusCodeArg(syn::Ident);

impl Default for StatusCodeArg {
    fn default() -> Self {
        Self(syn::parse_quote! { INTERNAL_SERVER_ERROR }) // 500
    }
}

impl ToTokens for StatusCodeArg {
    fn to_tokens(&self, tokens: &mut TokenStream) {
        let code = &self.0;
        tokens.extend(quote! { axum::http::StatusCode::#code })
    }
}

#[derive(Debug, FromField)]
struct FieldArgs {
    ident: Option<syn::Ident>,
    ty: syn::Type,
}

#[derive(Debug, FromMeta)]
struct StructArgs {
    #[darling(default)]
    status: StatusCodeArg,
}

// TODO: #[derive(Debug, FromMeta)] struct Context

pub fn view_error(input: &DeriveInput) -> Result<TokenStream> {
    let Args {
        ident,
        data,
        name,
        context,
        args: StructArgs { status },
    } = Args::from_derive_input(input)?;

    let label = name.unwrap_or_else(|| ident.to_string());

    let trait_impl = match data {
        ast::Data::Enum(variants) => {
            let status_impl = Matching::new(
                &ident,
                syn::parse_quote! { self },
                &variants,
                |VariantArgs { status, .. }| quote! { #status },
            );
            let variant_label_impl = Matching::new(
                &ident,
                syn::parse_quote! { self },
                &variants,
                |VariantArgs { ident, .. }| {
                    let label = ident.to_string();
                    quote! { Some(#label) }
                },
            );
            let context_impl = Matching::new(
                &ident,
                syn::parse_quote! { self },
                &variants,
                |VariantArgs {
                     fields,
                     context: variant_context,
                     ..
                 }| {
                    let context = if context || *variant_context {
                        context_of_fields(fields)
                    } else {
                        vec![]
                    };
                    quote! { std::collections::HashMap::from([#(#context),*]) }
                },
            )
            .destructuring();
            let responses = variants.iter().map(
                |VariantArgs {
                     fields,
                     status,
                     context: variant_context,
                     ..
                 }| {
                    Response::from_struct(context || *variant_context, status.clone(), fields)
                },
            );
            quote! {
                fn status(&self) -> axum::http::StatusCode {
                    #status_impl
                }
                fn variant_label(&self) -> Option<&'static str> {
                    #variant_label_impl
                }
                fn context(self) -> std::collections::HashMap<String, serde_json::Value> {
                    #context_impl
                }
                fn responses() -> Vec<crate::views::error::OpenApiResponse> {
                    Vec::from([#(#responses),*])
                }
            }
        }
        ast::Data::Struct(fields) => {
            let context_values = if context {
                context_of_fields(&fields)
            } else {
                vec![]
            };

            let response = Response::from_struct(context, status.clone(), &fields);

            quote! {
                fn status(&self) -> axum::http::StatusCode {
                    #status
                }

                fn context(&self) -> std::collections::HashMap<String, serde_json::Value> {
                    std::collections::HashMap::from([
                        #(#context_values),*
                    ])
                }

                fn responses() -> Vec<crate::views::error::OpenApiResponse> {
                    Vec::from([#response])
                }
            }
        }
    };

    Ok(quote! {
        impl crate::views::error::ViewError for #ident {
            const LABEL: &'static str = #label;
            #trait_impl
        }

        impl utoipa::IntoResponses for #ident {
            fn responses() -> std::collections::BTreeMap<
                String,
                utoipa::openapi::RefOr<utoipa::openapi::response::Response>,
            > {
                <Self as crate::views::error::ViewError>::utoipa_responses().into()
            }
        }

        impl axum::response::IntoResponse for #ident {
            fn into_response(self) -> axum::response::Response {
                <Self as crate::views::error::ViewError>::into_response(self)
            }
        }
    })
}

struct Response {
    variant: Option<String>,
    message_template: Option<String>,
    status: StatusCodeArg,
    context: Vec<(String, syn::Type)>,
}

impl ToTokens for Response {
    fn to_tokens(&self, tokens: &mut TokenStream) {
        let Self {
            variant,
            message_template,
            status,
            context,
        } = self;

        let context = context.iter().map(|(key, ty)| {
            quote! {
                crate::views::error::ContextEntry {
                    key: #key,
                    schema: <#ty as utoipa::PartialSchema>::schema()
                }
            }
        });

        let variant = variant
            .as_ref()
            .map(|v| quote! { Some(#v) })
            .unwrap_or(quote! { None });
        let message_template = message_template
            .as_ref()
            .map(|v| quote! { Some(#v) })
            .unwrap_or(quote! { None });

        tokens.extend(quote! {
            crate::views::error::OpenApiResponse {
                variant: #variant,
                message_template: #message_template,
                status: #status,
                context: Vec::from([#(#context),*]),
            }
        });
    }
}

struct Matching<'a, T> {
    ident: &'a syn::Ident,
    subject: syn::Expr,
    destructure_fields: bool,
    cases: Vec<(&'a VariantArgs, T)>,
}

impl<'a, T> Matching<'a, T> {
    fn new(
        ident: &'a syn::Ident,
        subject: syn::Expr,
        variants: &'a [VariantArgs],
        f: impl Fn(&'a VariantArgs) -> T,
    ) -> Self {
        Self {
            ident,
            subject,
            destructure_fields: false,
            cases: variants.iter().map(|v| (v, f(v))).collect(),
        }
    }

    fn destructuring(mut self) -> Self {
        self.destructure_fields = true;
        self
    }
}

impl<T: ToTokens> ToTokens for Matching<'_, T> {
    fn to_tokens(&self, tokens: &mut TokenStream) {
        let Self {
            ident,
            subject,
            destructure_fields,
            cases,
        } = self;
        let cases = cases.iter().map(|(variant, expression)| {
            let var_name = &variant.ident;
            let fields = variant
                .fields
                .iter()
                .enumerate()
                .map(|(pos, FieldArgs { ident, .. })| {
                    if let Some(ident) = ident {
                        ident.clone()
                    } else {
                        syn::Ident::new(&format!("_{pos}"), var_name.span())
                    }
                })
                .collect::<Vec<_>>();
            match (*destructure_fields, variant.fields.is_tuple()) {
                _ if variant.fields.is_unit() => quote! { #ident::#var_name => #expression },
                (_, true) => quote! { #ident::#var_name(#(#fields),*) => #expression },
                (true, false) => quote! { #ident::#var_name { #(#fields),* } => #expression },
                (false, false) => quote! { #ident::#var_name { .. } => #expression },
            }
        });
        tokens.extend(quote! {
            match #subject {
                #(#cases),*
            }
        });
    }
}

fn context_of_fields(fields: &ast::Fields<FieldArgs>) -> Vec<TokenStream> {
    fields
        .iter()
        .enumerate()
        .map(|(pos, FieldArgs { ident, .. })| {
            if let Some(ident) = ident {
                let key = ident.to_string();
                quote! { (#key.to_owned(), serde_json::to_value(#ident).expect("failed to serialize context value")) }
            } else {
                let ident = syn::Ident::new(&format!("_{pos}"), ident.span());
                let key = ident.to_string();
                quote! { (#key.to_owned(), serde_json::to_value(#ident).expect("failed to serialize context value")) }
            }
        })
        .collect()
}

impl Response {
    fn from_struct(
        context: bool,
        status: StatusCodeArg,
        fields: &darling::ast::Fields<FieldArgs>,
    ) -> Self {
        let context = if context && !fields.is_unit() {
            fields
                .iter()
                .enumerate()
                .map(|(pos, FieldArgs { ident, ty })| {
                    (
                        if let Some(ident) = ident {
                            ident.to_string()
                        } else {
                            format!("_{pos}")
                        },
                        ty.clone(),
                    )
                })
                .collect()
        } else {
            Vec::new()
        };
        Self {
            variant: None,
            message_template: None, // TODO
            status,
            context,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn struct_unit() {
        crate::assert_macro_expansion!(
            view_error,
            syn::parse_quote! {
                #[view_error(status = NOT_FOUND)]
                struct Unit;
            }
        );
    }

    #[test]
    fn struct_unit_with_context() {
        crate::assert_macro_expansion!(
            view_error,
            syn::parse_quote! {
                #[view_error(context)]
                struct UnitWithContext;
            }
        );
    }

    #[test]
    fn struct_newtype() {
        crate::assert_macro_expansion!(
            view_error,
            syn::parse_quote! {
                struct NewType(String);
            }
        );
    }

    #[test]
    fn struct_tuple() {
        crate::assert_macro_expansion!(
            view_error,
            syn::parse_quote! {
                struct Tuple(String, u32);
            }
        );
    }

    #[test]
    fn struct_tuple_with_context() {
        crate::assert_macro_expansion!(
            view_error,
            syn::parse_quote! {
                #[view_error(context)]
                struct TupleWithContext(String, u32);
            }
        );
    }

    #[test]
    fn struct_named() {
        crate::assert_macro_expansion!(
            view_error,
            syn::parse_quote! {
                #[view_error(status = UNAUTHORIZED)]
                struct Named {
                    field: String,
                }
            }
        );
    }

    #[test]
    fn struct_named_with_context() {
        crate::assert_macro_expansion!(
            view_error,
            syn::parse_quote! {
                #[view_error(context)]
                struct NamedWithContext {
                    cause: String,
                    fix: String,
                    incident_id: u64,
                }
            }
        );
    }

    #[test]
    fn enum_heterogeneous() {
        crate::assert_macro_expansion!(
            view_error,
            syn::parse_quote! {
                #[view_error(context)]
                enum Heterogeneous {
                    Unit, // default status code 400
                    #[view_error(status = INTERNAL_SERVER_ERROR)]
                    NewType(String),
                    #[view_error(status = BAD_REQUEST)]
                    Tuple(String, u32),
                    #[view_error(status = PAYMENT_REQUIRED)]
                    Struct {
                        cause: String,
                        fix: String,
                        incident_id: u64,
                    },
                }
            }
        );
    }
}
