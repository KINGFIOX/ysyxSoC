%option noinput nounput noyywrap nodefault

%{
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include "cpu/cpu_reg_view.h"
#include "expr.tab.h"

extern const npc::CpuRegView* expr_cpu;
extern bool expr_lexer_error;
extern int yyerror(const char* msg);
%}

%%
[ \t\r\n]+                ; /* whitespace */
0[xX][0-9a-fA-F]+         { yylval = strtoull(yytext, nullptr, 16); return TK_NUM; }
[0-9]+                    { yylval = strtoull(yytext, nullptr, 10); return TK_NUM; }
\$[a-zA-Z0-9_]+           {
                              if (expr_cpu != nullptr) {
                                auto result = expr_cpu->value(yytext + 1);
                                if (result.ok()) {
                                  yylval = *result;
                                } else {
                                  expr_lexer_error = true;
                                  yyerror("invalid register");
                                  yylval = 0;
                                }
                              } else {
                                expr_lexer_error = true;
                                yyerror("no CPU context");
                                yylval = 0;
                              }
                              return TK_REG;
                          }
"=="                      { return EQ; }
"!="                      { return NE; }
"<="                      { return LE; }
">="                      { return GE; }
"<"                       { return LT; }
">"                       { return GT; }
"&&"                      { return AND; }
"||"                      { return OR; }
"+"|"-"|"*"|"/"|"("|")"   { return yytext[0]; }
.                         { expr_lexer_error = true; yyerror("invalid character"); return yytext[0]; }
%%
