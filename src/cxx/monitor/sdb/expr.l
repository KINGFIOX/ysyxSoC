/* flex++ scanner for SDB expression evaluator */
%option c++
%option noyywrap nodefault nounput
%option yyclass="ExprScanner"

%{
#include "expr_scanner.hh"
#include <npc/isa.hh>
#include "expr.tab.hh"

/* yylval is defined by bison */
extern word_t yylval;
extern int yyerror(const char *msg);
%}

%%
[ \t\r\n]+                ;
0[xX][0-9a-fA-F]+         { yylval = std::strtoull(yytext, nullptr, 16); set_val(yylval); return TK_NUM; }
[0-9]+                    { yylval = std::strtoull(yytext, nullptr, 10); set_val(yylval); return TK_NUM; }
\$[a-zA-Z0-9_]+           {
                            auto v_opt = isa_reg_str2val(yytext + 1);
                            if (!v_opt) { set_error(); yyerror("invalid register"); yylval = 0; set_val(0); }
                            else { yylval = *v_opt; set_val(*v_opt); }
                            return TK_REG;
                          }
"=="                      { return EQ; }
"!="                      { return NE; }
"<"                       { return LT; }
"<="                      { return LE; }
">"                       { return GT; }
">="                      { return GE; }
"&&"                      { return AND; }
"||"                      { return OR; }
"+"|"-"|"*"|"/"|"("|")"   { return yytext[0]; }
.                         { set_error(); yyerror("invalid character"); return yytext[0]; }
%%
