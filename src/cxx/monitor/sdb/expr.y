/* Bison parser for SDB expression evaluator (compiled as C++) */
/* REFERENCE: https://github.com/sunxfancy/flex-bison-examples */

%{
#include <npc/common.hh>
#include <npc/isa.hh>
#include "sdb.hh"

int yylex(void);
int yyerror(const char *msg);

extern word_t parse_result;
extern bool runtime_error;

%}

%define api.value.type {word_t}
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
  | factor '/' unary { if ($3 == 0) { runtime_error = true; yyerror("division by zero"); $$ = 0; } else { $$ = (word_t)((sword_t)$1 / (sword_t)$3); } }
  ;

unary:
  primary { $$ = $1; }
  | '-' unary %prec UMINUS { $$ = (word_t)(-((sword_t)$2)); }
  | '*' unary %prec DEREF {
    if (in_flash($2) || in_sram($2) || in_psram($2) || in_sdram($2)) { $$ = vaddr_read($2, sizeof(word_t)); }
    else { $$ = 0xdeadbeef; runtime_error = true; yyerror("invalid memory access"); } }
  ;

primary:
  TK_NUM { $$ = $1; }
  | TK_REG { $$ = $1; }
  | '(' expression ')' { $$ = $2; }
  ;

%%
