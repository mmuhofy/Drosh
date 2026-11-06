//! The JNI types this library needs, declared by hand.
//!
//! Zig has no JNI binding and the NDK's `jni.h` is C++-flavoured in places.
//! The subset below is what a native library that returns primitives and
//! writes into caller-provided arrays actually uses: the environment pointer,
//! the class reference, and the calls needed to raise an exception and to read
//! and write a byte or int array.
//!
//! Everything is opaque: the layout of a `JNIEnv` is the JVM's business, and
//! these are only ever passed back to the functions that made them.

pub const jint = i32;
pub const jlong = i64;
pub const jbyte = i8;
pub const jboolean = u8;
pub const jsize = jint;
pub const jobject = ?*anyopaque;
pub const jclass = jobject;
pub const jstring = jobject;
pub const jarray = jobject;
pub const jintArray = jarray;
pub const jbyteArray = jarray;

/// A `JNIEnv*`.
///
/// In C this is a pointer to a struct whose first (and only) member is a
/// pointer to the function table. Flattened, it is a pointer to a pointer to
/// the table, which is what the calls below index.
pub const JNIEnv = *const *const ?*const anyopaque;

pub const JNI_FALSE: jboolean = 0;
pub const JNI_TRUE: jboolean = 1;

/// The JNI function-table indices this library uses.
///
/// The table is frozen by the JNI specification; the numbers are the ones in
/// `jni.h` and they have not moved since Java 1.1.
pub const Index = struct {
    pub const GetArrayLength = 171;
    pub const GetIntArrayRegion = 179;
    pub const SetIntArrayRegion = 188;
    pub const NewStringUTF = 167;
    pub const GetByteArrayRegion = 200;
    pub const SetByteArrayRegion = 208;
    pub const NewByteArray = 176;
    pub const ThrowNew = 14;
    pub const ExceptionOccurred = 15;
    pub const ExceptionClear = 16;
};

/// Call a JNI function by table index.
///
/// The cast is the JNI's own calling convention, not ours: the environment is
/// a pointer to an array of function pointers whose signatures vary per entry.
/// Each caller supplies the exact signature it is calling.
pub inline fn call(env: JNIEnv, comptime T: type, comptime index: usize) T {
    const table: [*]const ?*const anyopaque = @ptrCast(env.*);
    return @ptrCast(@alignCast(table[index].?));
}
