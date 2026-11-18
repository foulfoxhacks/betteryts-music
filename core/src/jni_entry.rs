//! JNI entry point for `dev.foulfoxhacks.betteryts.music.core.Core.call(method, argsJson)`.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_dev_foulfoxhacks_betteryts_music_core_Core_call<'local>(
	mut env: JNIEnv<'local>,
	_class: JClass<'local>,
	method: JString<'local>,
	args: JString<'local>,
) -> jstring {
	let method: String = match env.get_string(&method) {
		Ok(s) => s.into(),
		Err(_) => String::new(),
	};
	let args: String = env.get_string(&args).map(Into::into).unwrap_or_default();
	// A panic must never unwind into the JVM.
	let reply = std::panic::catch_unwind(|| crate::bridge::dispatch(&method, &args))
		.unwrap_or_else(|_| r#"{"ok":false,"error":"Something went wrong inside BetterYTS Music."}"#.to_string());
	match env.new_string(reply) {
		Ok(s) => s.into_raw(),
		Err(_) => std::ptr::null_mut(),
	}
}
