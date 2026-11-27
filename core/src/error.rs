//! Errors with a stable, machine-readable code, so the app can react to *what* went wrong
//! (offline, rate-limited, gone) instead of parsing message text.
//!
//! The bridge sends them as `{"ok":false,"error":"<message>","code":"RATE_LIMITED",
//! "retryable":true,"retryAfter":30}`; `error` stays a plain string so older app code keeps working.

use std::fmt;

/// Codes the app understands. Keep these stable: the Kotlin side matches on them.
pub mod code {
	/// No connection, DNS failure, TLS failure.
	pub const NETWORK: &str = "NETWORK";
	/// The request took too long.
	pub const TIMEOUT: &str = "TIMEOUT";
	/// HTTP 429; `retry_after` may say when to try again.
	pub const RATE_LIMITED: &str = "RATE_LIMITED";
	/// The server or a provider behind it failed (HTTP 5xx).
	pub const UPSTREAM: &str = "UPSTREAM";
	pub const NOT_FOUND: &str = "NOT_FOUND";
	/// The server refused the request (other 4xx).
	pub const REJECTED: &str = "REJECTED";
	/// The server answered with something that isn't the expected JSON.
	pub const BAD_RESPONSE: &str = "BAD_RESPONSE";
	pub const TOO_LARGE: &str = "TOO_LARGE";
	/// The app sent a bad argument (a bug, or junk input).
	pub const INVALID_ARGUMENT: &str = "INVALID_ARGUMENT";
	/// Anything else (library limits, validation messages).
	pub const ERROR: &str = "ERROR";
}

#[derive(Debug, Clone, PartialEq)]
pub struct CoreError {
	pub code: String,
	pub message: String,
	pub retryable: bool,
	/// Seconds, from the server's `Retry-After`.
	pub retry_after: Option<u64>,
	/// HTTP status, when there was one.
	pub status: Option<u16>,
	/// The server's request ID (`X-Request-Id` or the v1 envelope's `meta.requestId`), for support.
	pub request_id: Option<String>,
}

impl CoreError {
	pub fn new(code: &str, message: impl Into<String>) -> Self {
		let retryable = matches!(code, code::NETWORK | code::TIMEOUT | code::RATE_LIMITED | code::UPSTREAM);
		CoreError { code: code.to_string(), message: message.into(), retryable, retry_after: None, status: None, request_id: None }
	}

	pub fn invalid(message: impl Into<String>) -> Self {
		CoreError::new(code::INVALID_ARGUMENT, message)
	}

	/// The error for an HTTP status. `server_code` (from a v1 error body) wins when present.
	pub fn from_status(status: u16, message: impl Into<String>, server_code: Option<&str>) -> Self {
		let c = match status {
			429 => code::RATE_LIMITED,
			404 | 410 => code::NOT_FOUND,
			413 => code::TOO_LARGE,
			500..=599 => code::UPSTREAM,
			_ => code::REJECTED,
		};
		let mut e = CoreError::new(c, message);
		if let Some(sc) = server_code.filter(|s| is_code(s)) {
			e.code = sc.to_string();
		}
		e.status = Some(status);
		e
	}

	pub fn with_retry_after(mut self, secs: Option<u64>) -> Self {
		self.retry_after = secs;
		self
	}

	pub fn with_request_id(mut self, id: Option<String>) -> Self {
		self.request_id = id;
		self
	}
}

/// A server-sent code must look like one (`UPPER_SNAKE`, short), or it's ignored.
fn is_code(s: &str) -> bool {
	!s.is_empty() && s.len() <= 40 && s.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || c == '_')
}

impl fmt::Display for CoreError {
	fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
		f.write_str(&self.message)
	}
}

impl std::error::Error for CoreError {}

/// Existing code returns `Result<_, String>`; those messages become generic errors.
impl From<String> for CoreError {
	fn from(message: String) -> Self {
		CoreError::new(code::ERROR, message)
	}
}

impl From<&str> for CoreError {
	fn from(message: &str) -> Self {
		CoreError::new(code::ERROR, message)
	}
}

/// And the other way, so `?` on a typed error still works in String-returning code.
impl From<CoreError> for String {
	fn from(e: CoreError) -> Self {
		e.message
	}
}

/// `Retry-After` as seconds. HTTP-dates are rare from our Worker and are ignored.
pub fn parse_retry_after(v: Option<&str>) -> Option<u64> {
	v?.trim().parse::<u64>().ok()
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn status_codes_map_to_stable_codes() {
		assert_eq!(CoreError::from_status(429, "slow down", None).code, code::RATE_LIMITED);
		assert!(CoreError::from_status(429, "", None).retryable);
		assert_eq!(CoreError::from_status(404, "", None).code, code::NOT_FOUND);
		assert!(!CoreError::from_status(404, "", None).retryable);
		assert_eq!(CoreError::from_status(503, "", None).code, code::UPSTREAM);
		assert_eq!(CoreError::from_status(400, "", None).code, code::REJECTED);
		assert_eq!(CoreError::from_status(400, "", Some("CODE_EXPIRED")).code, "CODE_EXPIRED", "server codes win");
		assert_eq!(CoreError::from_status(400, "", Some("<script>")).code, code::REJECTED, "junk codes are ignored");
	}

	#[test]
	fn retry_after_and_conversions() {
		assert_eq!(parse_retry_after(Some(" 30 ")), Some(30));
		assert_eq!(parse_retry_after(Some("Wed, 21 Oct 2026 07:28:00 GMT")), None);
		assert_eq!(parse_retry_after(None), None);
		let e: CoreError = "Playlist limit".to_string().into();
		assert_eq!((e.code.as_str(), e.retryable), (code::ERROR, false));
		let s: String = CoreError::new(code::NETWORK, "offline").into();
		assert_eq!(s, "offline");
	}
}
