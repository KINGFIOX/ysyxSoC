/* REFERENCE: https://github.com/sunxfancy/flex-bison-examples */

%{
#include <common.h> /* 引入头文件 */
#include <memory/vaddr.h>
#include <memory/paddr.h>
#include <isa.h>
#include "sdb.h"

typedef struct yy_buffer_state * YY_BUFFER_STATE;
int sdb_exprlex(void); /* 词法分析器 */
YY_BUFFER_STATE sdb_expr_scan_string(const char *yy_str); /* 创建该字符串对应的状态机 */
void sdb_expr_delete_buffer(YY_BUFFER_STATE b); /* 释放状态机 */
int sdb_exprlex_destroy(void); /* 销毁词法分析器 */
int sdb_exprerror(const char *msg); /* 错误处理 handler */

/* 全局变量 error */
static word_t parse_result; /* 求值结果 */

/* 报错信息 */
const char * parse_error_msg = NULL;
bool parse_error;
bool sdb_expr_lexer_error; /* yy_lexer_error -> sdb_expr_lexer_error */
static bool runtime_error;

%}

%define api.prefix {sdb_expr} /* 定义前缀, yy_scan_string -> sdb_expr_scan_string, etc. */
%define api.value.type {word_t} /* 定义值类型, $x 是 word_t 类型的 */
%define parse.error verbose /* 定义错误处理方式 */

%token TK_NUM TK_REG
%token EQ NE LT LE GT GE
%token AND OR

%left OR
%left AND
%left EQ NE LT LE GT GE
%left '+' '-'
%left '*' '/' /* 放在 +- 后面, 意味着更高的优先级 */
%right UMINUS DEREF

%%

/* BNF: https://craftinginterpreters.com/parsing-expressions.html#design-note */

expression:
  logic_or { parse_result = $1; }
  ;

logic_or:
  logic_and { $$ = $1; }
  | logic_or OR logic_and { $$ = $1 || $3; }
  ;

logic_and:
  equality { $$ = $1; }
  | logic_and AND equality { $$ = $1 && $3; }
  ;

equality:
  comparison { $$ = $1; }
  | equality EQ equality { $$ = ((sword_t)$1 == (sword_t)$3); }
  | equality NE equality { $$ = ((sword_t)$1 != (sword_t)$3); }
  ;

comparison:
  term { $$ = $1; }
  | comparison LT term { $$ = ((sword_t)$1 <  (sword_t)$3); }
  | comparison LE term { $$ = ((sword_t)$1 <= (sword_t)$3); }
  | comparison GT term { $$ = ((sword_t)$1 >  (sword_t)$3); }
  | comparison GE term { $$ = ((sword_t)$1 >= (sword_t)$3); }
  ;

term:
  factor { $$ = $1; }
  | term '-' factor { $$ = (word_t)((sword_t)$1 - (sword_t)$3); }
  | term '+' factor { $$ = (word_t)((sword_t)$1 + (sword_t)$3); }
  ;

factor:
  unary { $$ = $1; }
  | factor '*' unary { $$ = (word_t)((sword_t)$1 * (sword_t)$3); }
  | factor '/' unary { if ($3 == 0) { runtime_error = true; sdb_exprerror("division by zero"); $$ = 0; } else { $$ = (word_t)((sword_t)$1 / (sword_t)$3); } }
  ;

unary:
  primary { $$ = $1; }
  | '-' unary %prec UMINUS { $$ = (word_t)(-((sword_t)$2)); }
  | '*' unary %prec DEREF { if (likely(in_pmem($2))) { $$ = vaddr_read($2, sizeof(word_t)); } else { $$ = 0xdeadbeef; runtime_error = true; sdb_exprerror("invalid memory access"); } } /* 解引用 */
  ;

primary:
  TK_NUM { $$ = $1; }
  | TK_REG { $$ = $1; }
  | '(' expression ')' { $$ = $2; }
  ;

%%

word_t expr_eval(const char *expr_str, bool *success) {
  parse_result = 0;
  parse_error = false;
  sdb_expr_lexer_error = false; /* 词法分析错误: 无效字符, 无效寄存器 */
  runtime_error = false; /* 运行时错误: 除以0 或者无效的解引用 */

  YY_BUFFER_STATE buf = sdb_expr_scan_string(expr_str);
  int ret = sdb_exprparse(); /* 表达式求值 */
  sdb_expr_delete_buffer(buf);
  sdb_exprlex_destroy(); /* 销毁词法分析器 */

  bool ok = (ret == 0) && !parse_error && !sdb_expr_lexer_error && !runtime_error;
  if (success) { *success = ok; }
  return ok ? parse_result : -1;
}

int sdb_exprerror(const char *msg) {
  parse_error = true;
  parse_error_msg = msg;
  return -1;
}

