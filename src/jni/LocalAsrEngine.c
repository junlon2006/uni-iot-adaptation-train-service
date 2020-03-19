#include "LocalAsrEngine.h"
#include "jni.h"
#include "ual_ofa.h"
#include "ofa_consts.h"
#include "log.h"

#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <pthread.h>
#include <dirent.h>
#include <sys/stat.h>
#include "list_head.h"

#define MAXPATH       (2048)
#define TAG           "Engine"
#define DOMAIN_ID     (54)
#define min(a, b)     (a > b ? b : a)
#define PACKED        __attribute__ ((packed))
#define VERSION       "asr_v5.1.4__wrapper_v1.1.0"

typedef struct {
  char  riff[4];
  int   file_len;
  char  wave[4];
  char  fmt[4];
  char  filter[4];
  short type;
  short channel;
  int   sample_rate;
  int   bitrate;
  short adjust;
  short bit;
  char  data[4];
  int   audio_len;
} PACKED WavHeader;

typedef struct {
  list_head link;
  char      *chinese_index;
  char      *chinese_word;
} KeyWordMapping;

static jboolean  g_is_engine_inited = false;
static list_head g_cmd_mapping_list;

static void _create_cmd_mapping(const char *cmd_mapping, Log &log) {
  char c;
  char buf[1024];
  int index = 0;
  KeyWordMapping *item;

  list_init(&g_cmd_mapping_list);

  while (c = *cmd_mapping++) {
    if (c == '|') {
      buf[index] = '\0';
      index = 0;
      item = (KeyWordMapping *)malloc(sizeof(KeyWordMapping));
      list_init(&item->link);

      item->chinese_index = (char *)malloc(strlen(buf) + 1);
      strcpy(item->chinese_index, buf);
      log.info("chinese_index={}", item->chinese_index);
      continue;
    }

    if (c == ';') {
      buf[index] = '\0';
      index = 0;
      item->chinese_word = (char *)malloc(strlen(buf) + 1);
      strcpy(item->chinese_word, buf);
      log.info("chinese_word={}", item->chinese_word);

      list_add_tail(&item->link, &g_cmd_mapping_list);
      continue;
    }

    buf[index++] = c;
  }

  log.info("create mapping done");
}

static const char* _get_chinese_word_by_wav_file_name(const char *wav_file_name, Log &log) {
  KeyWordMapping *p;
  list_for_each_entry(p, &g_cmd_mapping_list, KeyWordMapping, link) {
    if (NULL != strstr(wav_file_name, p->chinese_index)) {
      log.info("find cmd={}", p->chinese_word);
      return p->chinese_word;
    }
  }

  log.error("fatal error, never touch here, name={}", wav_file_name);
  return "N/A";
}

static void _destroy_mapping_list(Log &log) {
  KeyWordMapping *p, *t;
  list_for_each_entry_safe(p, t, &g_cmd_mapping_list, KeyWordMapping, link) {
    free(p->chinese_index);
    free(p->chinese_word);
    free(p);
  }

  log.info("destroy mapping done");
}

static int _is_wav_format(WavHeader *wavheader, int len) {
  return (wavheader->riff[0] == 'R' &&
      wavheader->riff[1] == 'I' &&
      wavheader->riff[2] == 'F' &&
      wavheader->riff[3] == 'F' &&
      wavheader->wave[0] == 'W' &&
      wavheader->wave[1] == 'A' &&
      wavheader->wave[2] == 'V' &&
      wavheader->wave[3] == 'E' &&
      wavheader->audio_len == len &&
      wavheader->channel == 1 &&
      wavheader->sample_rate == 16000 &&
      wavheader->bit == 16);
}

static int _wav_2_raw_pcm(char *file_name, char **raw_data, int *len, Log &log) {
  int fd;
  WavHeader wav_header = {0};

  log.info("process [{}]", file_name);

  fd = open(file_name, O_RDWR, 0664);
  if (fd < 0) {
    log.error("open file[{}] failed", file_name);
    return -1;
  }

  *len = lseek(fd, 0, SEEK_END) - sizeof(WavHeader);
  lseek(fd, 0, SEEK_SET);

  if (sizeof(WavHeader) != read(fd, &wav_header, sizeof(WavHeader))) {
    log.error("read wavheader faild");
    goto L_ERROR;
  }

  if (!_is_wav_format(&wav_header, *len)) {
    log.error("not wav format, invalid");
    goto L_ERROR;
  }

  *raw_data = (char *)malloc(*len);
  memset(*raw_data, 0, *len);
  if (*len != read(fd, *raw_data, *len)) {
    log.error("read raw data failed");
    free(*raw_data);
    goto L_ERROR;
  }

  close(fd);
  return 0;

L_ERROR:
  close(fd);
  return -1;
}

static void _get_workspace(char *path, int len, Log &log) {
  if (NULL == getcwd(path, len)) {
    log.error("get workspace failed");
    return;
  }
}

static void _get_am_path(char *am_path, int len, Log &log) {
  char workspace[MAXPATH];
  _get_workspace(workspace, sizeof(workspace), log);
  snprintf(am_path, len, "%s/%s", workspace, "models");
  log.info("am path={}", am_path);
}

static void _get_grammar(char *grammar, int len, const char *grammar_name, Log &log) {
  char workspace[MAXPATH];
  _get_workspace(workspace, sizeof(workspace), log);
  snprintf(grammar, len, "%s/grammar/%s", workspace, grammar_name);
  log.info("grammar={}", grammar);
}

static void _engine_final(Log &log) {
  if (!g_is_engine_inited) {
    return;
  }

  log.info("release engine");
  UalOFARelease();
}

static void _create_directory(const char *grammar_name, Log &log) {
  char workspace[MAXPATH];
  char path[MAXPATH];
  char command[MAXPATH];
  _get_workspace(workspace, sizeof(workspace), log);
  snprintf(path, sizeof(path), "%s/out/%s", workspace, grammar_name);
  mkdir(path, S_IRWXU);

  snprintf(command, sizeof(command), "rm -rf %s/*", path);
  system(command);
}

static int _engine_init(const char *grammar_name, Log &log) {
  int status;
  char am_path[MAXPATH];
  char grammar[MAXPATH];

  int engineType = UalOFAGetEngineType();
  if (KWS_STD_ENGINE != engineType) {
    log.warn("engine type={}", engineType);
    goto L_ERROR;
  }

  log.info("version={}", UalOFAGetVersion());
  UalOFASetOptionInt(ASR_LOG_ID, 0);

  _get_am_path(am_path, sizeof(am_path), log);
  _get_grammar(grammar, sizeof(grammar), grammar_name, log);
  log.info("am={}, grammar={}", am_path, grammar);
  status = UalOFAInitialize(am_path, grammar);
  if (0 != status) {
    log.error("init failed, rc={}", status);
    goto L_ERROR;
  }

  status = UalOFASetOptionInt(ASR_ENGINE_SET_TYPE_ID, KWS_STD_ENGINE);
  if (status == ASR_FATAL_ERROR) {
    log.error("init failed, rc={}", status);
    goto L_ERROR;
  }

  UalOFASetOptionInt(ASR_SET_BEAM_ID, 1500);
  UalOFASetOptionInt(ASR_ENGINE_SWITCH_LANGUAGE, MANDARIN);
  _create_directory(grammar_name, log);
  g_is_engine_inited = true;
  log.info("engine init success");
  return 0;

L_ERROR:
  g_is_engine_inited = false;
  log.error("engine init failed");
  return -1;
}

static void _get_wav_file_path(char *wav_path, int len, const char *grammar_name, Log &log) {
  char workspace[MAXPATH];
  _get_workspace(workspace, sizeof(workspace), log);
  snprintf(wav_path, len, "%s/wav/%s", workspace, grammar_name);
}

static void _get_txt_file_path(char *txt_path, int len, const char *grammar_name, Log &log) {
  char workspace[MAXPATH];
  _get_workspace(workspace, sizeof(workspace), log);
  snprintf(txt_path, len, "%s/out/%s", workspace, grammar_name);
}

static void _get_begin_end_time(float *start_ms, float *stop_ms) {
  *start_ms = UalOFAGetOptionInt(ASR_ENGINE_UTTERANCE_START_TIME) / 1000.0;
  *stop_ms = UalOFAGetOptionInt(ASR_ENGINE_UTTERANCE_STOP_TIME) / 1000.0;
}

static void _write_recognize_2_txt_file(const char *txt_file_name,
                                        char *txt_result) {
  int fd = open(txt_file_name, O_RDWR | O_CREAT | O_APPEND | O_SYNC, 0664);
  if (fd < 0) {
    printf("open file[%s] failed\n", txt_file_name);
    return;
  }

  write(fd, txt_result, strlen(txt_result));
  close(fd);
}

static void _get_asr_result(const char *file_name, const char *wav_file_name,
                            jboolean *asr_ok, jboolean stop_try, Log &log) {
  char *res = UalOFAGetResult();
  float start_msec, stop_msec;
  char txt_result[256];
  if ((stop_try && *asr_ok) || NULL != strstr(res, "#NULL")) {
    return;
  }

  _get_begin_end_time(&start_msec, &stop_msec);
  log.info("asr:{}, start={}, stop={}", res, start_msec, stop_msec);
  log.info("txt_file_name={}", file_name);
  log.info("wav_file_name={}", wav_file_name);
  snprintf(txt_result, sizeof(txt_result), "%s\t%f\t%f\t%s\n", wav_file_name,
           start_msec, stop_msec, _get_chinese_word_by_wav_file_name(wav_file_name, log));
  log.info("txt_result={}", txt_result);
  _write_recognize_2_txt_file(file_name, txt_result);
  *asr_ok = true;
}

static int _mark_one(const char *txt_file_name, const char *wav_file_name,
                     const char *grammar, char *raw_data, int len, Log &log) {
  int fix_one_recongize_len = 160;
  int i = 0;
  int status;
  jboolean asr_ok = false;

  status = UalOFAStart(grammar, DOMAIN_ID);
  if (status != ASR_RECOGNIZER_OK) {
    log.error("start failed, errno={}", status);
  }

  while (len > 0) {
    status = UalOFARecognize((signed char *)(raw_data + (fix_one_recongize_len * i++)),
                             min(fix_one_recongize_len, len));
    if (status == ASR_RECOGNIZER_PARTIAL_RESULT) {
      _get_asr_result(txt_file_name, wav_file_name, &asr_ok, false, log);
    }

    len -= fix_one_recongize_len;
  }

  status = UalOFAStop();
  if (status >= 0) {
    _get_asr_result(txt_file_name, wav_file_name, &asr_ok, true, log);
  }

  if (!asr_ok) {
    log.error("cannot mark wav file={}", wav_file_name);
  }

  return asr_ok ? 0 : -1;
}

static void _wav_2_txt(char *file_name) {
  int len = strlen(file_name);
  file_name[len - 3] = 't';
  file_name[len - 2] = 'x';
  file_name[len - 1] = 't';
}

static void _get_txt_result_file_name(char *file_name, int len, const char *txt_path) {
  snprintf(file_name, len, "%s/result.txt", txt_path);
}

static int _mark_one_by_one(const char *wav_path, const char *txt_path,
                            const char *grammar, const char *cmd_mapping, Log &log) {
  DIR *dir;
  struct dirent *ent;
  char file_name[MAXPATH];
  char txt_result_file_name[MAXPATH];
  char *raw_data = NULL;
  int len;
  int mark_result = -1;

  if (NULL == (dir = opendir(wav_path))) {
    log.error("open dir [{}] failed", wav_path);
    return -1;
  }

  log.info("grammar={}, cmd_mapping={}", grammar, cmd_mapping);

  _create_cmd_mapping(cmd_mapping, log);

  _get_txt_result_file_name(txt_result_file_name, sizeof(txt_result_file_name), txt_path);

  while ((ent = readdir(dir)) != NULL) {
    if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) {
      continue;
    }

    sprintf(file_name, "%s/%s", wav_path, ent->d_name);
    if (0 != (mark_result = _wav_2_raw_pcm(file_name, &raw_data, &len, log))) {
      if (raw_data != NULL) {
        free(raw_data);
      }

      break;
    }

    mark_result = _mark_one(txt_result_file_name, ent->d_name, grammar, raw_data, len, log);
    if (mark_result != 0) {
      log.error("mark failed, cannot recognize audio, fatal error");
      free(raw_data);
      break;
    }

    free(raw_data);
    raw_data = NULL;
  }

  _destroy_mapping_list(log);
  return mark_result;
}

static int _mark_process(const char *grammar_name, const char *cmd_mapping, Log &log) {
  char wav_path[MAXPATH];
  char txt_path[MAXPATH];

  _get_wav_file_path(wav_path, sizeof(wav_path), grammar_name, log);
  _get_txt_file_path(txt_path, sizeof(txt_path), grammar_name, log);
  log.info("wav_path={}, txt_path={}", wav_path, txt_path);

  return _mark_one_by_one(wav_path, txt_path, grammar_name, cmd_mapping, log);
}

static void _remove_grammar(const char *grammar_name, Log &log) {
  char grammar[MAXPATH];
  char command[MAXPATH];
  _get_grammar(grammar, sizeof(grammar), grammar_name, log);
  snprintf(command, sizeof(command), "rm %s", grammar);
  log.info("remove grammar cmd={}", command);
  system(command);
}

JNIEXPORT jint JNICALL Java_com_unisound_aios_adaptationtrain_JNI_LocalAsrEngine_markProcess
(JNIEnv *env, jobject obj, jstring grammar, jstring cmd_word) {
  const char *grammar_name = env->GetStringUTFChars(grammar, 0);
  const char *cmd_mapping = env->GetStringUTFChars(cmd_word, 0);

  Log log(env, obj);

  system("sync");
  log.info("grammar_name={}, cmd_mapping={}, version={}", grammar_name, cmd_mapping, VERSION);

  int ret = _engine_init(grammar_name, log);
  if (ret == 0) {
    ret = _mark_process(grammar_name, cmd_mapping, log);
  }
  _remove_grammar(grammar_name, log);
  _engine_final(log);

  env->ReleaseStringUTFChars(grammar, grammar_name);
  env->ReleaseStringUTFChars(cmd_word, cmd_mapping);

  system("sync");
  return ret;
}
