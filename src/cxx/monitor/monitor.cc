#include <npc/cpu.hh>
#include <npc/difftest.hh>
#include <npc/isa.hh>
#include <npc/log.hh>
#include <npc/state.hh>

#include <cstdlib>
#include <cstring>
#include <getopt.h>
#include <string_view>

void init_log(const char *log_file);
long init_flash(const char *img_file);
void init_sdb();
void sdb_set_batch_mode();

namespace {

struct MonitorArgs {
  const char *log_file = nullptr;
  char *diff_so_file = nullptr;
  const char *img_file = nullptr;
  int difftest_port = 1234;
};

MonitorArgs parse_args(int argc, char *argv[]) {
  MonitorArgs args;

  static constexpr struct option long_options[] = {
      {"batch", no_argument, nullptr, 'b'},
      {"log", required_argument, nullptr, 'l'},
      {"diff", required_argument, nullptr, 'd'},
      {"port", required_argument, nullptr, 'p'},
      {"flash", required_argument, nullptr, 'f'},
      {"help", no_argument, nullptr, 'h'},
      {nullptr, 0, nullptr, 0},
  };

  int o;
  while ((o = getopt_long(argc, argv, "-bhl:d:p:f:", long_options, nullptr)) != -1) {
    switch (o) {
    case 'b': sdb_set_batch_mode(); break;
    case 'p': std::sscanf(optarg, "%d", &args.difftest_port); break;
    case 'l': args.log_file = optarg; break;
    case 'd': args.diff_so_file = optarg; break;
    case 1:   args.img_file = optarg; return args;
    default:
      std::printf("Usage: %s [OPTION...] IMAGE [args]\n\n", argv[0]);
      std::printf("\t-b,--batch              run with batch mode\n");
      std::printf("\t-l,--log=FILE           output log to FILE\n");
      std::printf("\t-d,--diff=REF_SO        run DiffTest with reference REF_SO\n");
      std::printf("\t-p,--port=PORT          run DiffTest with port PORT\n");
      std::printf("\t-f,--flash=FILE         load flash content from FILE\n\n");
      std::exit(0);
    }
  }
  return args;
}

void welcome() {
  Log("Trace: {}", MUXDEF(CONFIG_TRACE, ANSI_FMT("ON", ANSI_FG_GREEN),
                          ANSI_FMT("OFF", ANSI_FG_RED)));
  IFDEF(CONFIG_TRACE,
        Log("If trace is enabled, a log file will be generated "
            "to record the trace. This may lead to a large log file. "
            "If it is not necessary, you can disable it in menuconfig"));
  Log("Build time: {}, {}", __TIME__, __DATE__);
  std::printf("Welcome to %s-NPC!\n",
              ANSI_FMT(NPC_STR(__GUEST_ISA__), ANSI_FG_YELLOW ANSI_BG_RED));
  std::printf("For help, type \"help\"\n");
}

} // anonymous namespace

void init_monitor(int argc, char *argv[]) {
  auto args = parse_args(argc, argv);

  init_rand();
  init_log(args.log_file);

  long img_size = init_flash(args.img_file);
  init_mem();
  init_isa();
  npc_core_init(argc, argv);

  IFDEF(CONFIG_FTRACE, init_ftrace(args.img_file));
  init_difftest(args.diff_so_file, img_size, args.difftest_port);
  init_sdb();
  IFDEF(CONFIG_ITRACE, init_disasm());

  welcome();
}
