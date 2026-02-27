#include "sdb.hh"
#include "expr.tab.hh"
#include "expr_scanner.hh"

#include <optional>
#include <sstream>

static ExprScanner *g_scanner = nullptr;

word_t parse_result = 0;
bool yyerror_set = false;
bool runtime_error = false;
const char *parse_error_msg = nullptr;

int yylex(void) {
  if (!g_scanner) return 0;
  return g_scanner->yylex();
}

int yyerror(const char *msg) {
  yyerror_set = true;
  parse_error_msg = msg;
  return -1;
}

std::optional<word_t> expr_eval(const char *expr_str) {
  std::istringstream iss(expr_str);
  ExprScanner scanner(iss);
  g_scanner = &scanner;

  parse_result = 0;
  yyerror_set = false;
  runtime_error = false;

  int ret = yyparse();
  g_scanner = nullptr;

  bool ok = (ret == 0) && !yyerror_set && !scanner.has_error() && !runtime_error;
  return ok ? std::optional<word_t>(parse_result) : std::nullopt;
}
