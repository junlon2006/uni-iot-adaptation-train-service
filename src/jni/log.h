#ifndef LOG_HH
#define LOG_HH

#include "jni.h"
#include <string>
#include <assert.h>

class Log {
public:
  template<typename... Ts>
  void info(const std::string &string, Ts &&... args);

  template<typename... Ts>
  void warn(const std::string &string, Ts &&... args);

  template<typename... Ts>
  void error(const std::string &string, Ts &&... args);

  Log(JNIEnv *env, jobject source);

private:
  JNIEnv *env;

  const jobject object;
  const jmethodID infoMethod;
  const jmethodID warnMethod;
  const jmethodID errorMethod;

  inline jobject &toJava(jobject &value);
  inline jstring toJava(const char *value);
  inline jstring toJava(const std::string &value);
  inline jobject toJava(int value);

  inline void toArgArray(JNIEnv *env, jobjectArray &array, int position);

  template<typename T, typename... Ts>
  inline void toArgArray(JNIEnv *env, jobjectArray &array, int position, T &&current, Ts &&... args);
};

template<typename... Ts>
void Log::info(const std::string &format, Ts &&... args) {
  auto argArray = env->NewObjectArray(sizeof...(args), env->FindClass("java/lang/Object"), nullptr);
  toArgArray(env, argArray, 0, std::forward<Ts>(args)...);

  env->CallVoidMethod(object, infoMethod, toJava(format), argArray);
}

template<typename... Ts>
void Log::warn(const std::string &format, Ts &&... args) {
  auto argArray = env->NewObjectArray(sizeof...(args), env->FindClass("java/lang/Object"), nullptr);
  toArgArray(env, argArray, 0, std::forward<Ts>(args)...);

  env->CallVoidMethod(object, warnMethod, toJava(format), argArray);
}

template<typename... Ts>
void Log::error(const std::string &format, Ts &&... args) {
  auto argArray = env->NewObjectArray(sizeof...(args), env->FindClass("java/lang/Object"), nullptr);
  toArgArray(env, argArray, 0, std::forward<Ts>(args)...);

  env->CallVoidMethod(object, errorMethod, toJava(format), argArray);
}

void Log::toArgArray(JNIEnv *env, jobjectArray &array, int) {
}

template<typename T, typename... Ts>
void Log::toArgArray(JNIEnv *env, jobjectArray &array, int position, T &&current, Ts &&... args) {
  env->SetObjectArrayElement(array, position, toJava(std::forward<T>(current)));

  toArgArray(env, array, position + 1, std::forward<Ts>(args)...);
}

jobject &Log::toJava(jobject &value) {
  return value;
}

jstring Log::toJava(const char *value) {
  return env->NewStringUTF(value);
}

jstring Log::toJava(const std::string &value) {
  return env->NewStringUTF(value.c_str());
}

jobject Log::toJava(int value) {
  auto class_ = env->FindClass("java/lang/Integer");
  assert(class_);

  auto ctor = env->GetMethodID(class_, "<init>", "(I)V");
  assert(ctor);

  return env->NewObject(class_, ctor, value);
}

#endif
