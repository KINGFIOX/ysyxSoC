#ifndef NPC_SDB_HH_
#define NPC_SDB_HH_

#include <npc/common.hh>
#include <optional>
#include <string_view>

// Expression evaluator (implemented via flex++/bison)
std::optional<word_t> expr_eval(const char *expr);

extern const char *parse_error_msg;

// Watchpoint management
void init_wp_pool();
int add_watchpoint(const char *expr);
bool delete_watchpoint(int no);
void list_watchpoints();
bool check_watchpoints();

#endif // NPC_SDB_HH_
