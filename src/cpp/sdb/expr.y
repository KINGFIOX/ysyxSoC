%{
#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "cpu/cpu_reg_view.h"

typedef struct yy_buffer_state* YY_BUFFER_STATE;
int yylex(void);
YY_BUFFER_STATE yy_scan_string(const char* yy_str);
void yy_delete_buffer(YY_BUFFER_STATE b);
int yylex_destroy(void);
int yyerror(const char* msg);

static uint64_t parse_result;
const char* expr_parse_error_msg = nullptr;
bool expr_parse_error;
bool expr_lexer_error;
static bool runtime_error;

const npc::CpuRegView* expr_cpu = nullptr;

%}

%define api.value.type {uint64_t}
%define parse.error verbose

%token TK_NUM TK_REG
%token EQ NE LT LE GT GE
%token AND OR

%left OR
%left AND
%left EQ NE LT LE GT GE
%left '+' '-'
%left '*' '/'
%right UMINUS DEREF

%%

expression:
  logic_or { parse_result = $1; }
  ;

logic_or:
  logic_and                   { $$ = $1; }
  | logic_or OR logic_and     { $$ = ($1 != 0) || ($3 != 0); }
  ;

logic_and:
  equality                    { $$ = $1; }
  | logic_and AND equality    { $$ = ($1 != 0) && ($3 != 0); }
  ;

equality:
  comparison                  { $$ = $1; }
  | equality EQ comparison    { $$ = ((int64_t)$1 == (int64_t)$3); }
  | equality NE comparison    { $$ = ((int64_t)$1 != (int64_t)$3); }
  ;

comparison:
  term                        { $$ = $1; }
  | comparison LT term        { $$ = ((int64_t)$1 <  (int64_t)$3); }
  | comparison LE term        { $$ = ((int64_t)$1 <= (int64_t)$3); }
  | comparison GT term        { $$ = ((int64_t)$1 >  (int64_t)$3); }
  | comparison GE term        { $$ = ((int64_t)$1 >= (int64_t)$3); }
  ;

term:
  factor                      { $$ = $1; }
  | term '+' factor           { $$ = (uint64_t)((int64_t)$1 + (int64_t)$3); }
  | term '-' factor           { $$ = (uint64_t)((int64_t)$1 - (int64_t)$3); }
  ;

factor:
  unary                       { $$ = $1; }
  | factor '*' unary          { $$ = (uint64_t)((int64_t)$1 * (int64_t)$3); }
  | factor '/' unary          {
      if ($3 == 0) {
        runtime_error = true;
        yyerror("division by zero");
        $$ = 0;
      } else {
        $$ = (uint64_t)((int64_t)$1 / (int64_t)$3);
      }
    }
  ;

unary:
  primary                     { $$ = $1; }
  | '-' unary %prec UMINUS   { $$ = (uint64_t)(-((int64_t)$2)); }
  | '*' unary %prec DEREF    {
      if (expr_cpu != nullptr) {
        auto result = expr_cpu->mem_load($2, 4);
        if (result.ok()) {
          $$ = *result;
        } else {
          $$ = 0xdeadbeef;
          runtime_error = true;
          yyerror("invalid memory access");
        }
      } else {
        $$ = 0xdeadbeef;
        runtime_error = true;
        yyerror("no CPU context for memory access");
      }
    }
  ;

primary:
  TK_NUM                      { $$ = $1; }
  | TK_REG                    { $$ = $1; }
  | '(' expression ')'        { $$ = $2; }
  ;

%%

uint64_t expr_eval(const char* expr_str, const npc::CpuRegView* cpu,
                   bool* success) {
  parse_result = 0;
  expr_parse_error = false;
  expr_lexer_error = false;
  runtime_error = false;
  expr_cpu = cpu;

  YY_BUFFER_STATE buf = yy_scan_string(expr_str);
  int ret = yyparse();
  yy_delete_buffer(buf);
  yylex_destroy();
  expr_cpu = nullptr;

  bool ok = (ret == 0) && !expr_parse_error && !expr_lexer_error &&
            !runtime_error;
  if (success) *success = ok;
  return ok ? parse_result : static_cast<uint64_t>(-1);
}

int yyerror(const char* msg) {
  expr_parse_error = true;
  expr_parse_error_msg = msg;
  return -1;
}
