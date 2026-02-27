#ifndef EXPR_SCANNER_HH_
#define EXPR_SCANNER_HH_

#if !defined(yyFlexLexerOnce)
#include <FlexLexer.h>
#endif

#include <npc/common.hh>

class ExprScanner : public yyFlexLexer {
public:
  ExprScanner(std::istream &in) : yyFlexLexer(&in) {}

  using yyFlexLexer::yylex;
  int yylex() override;

  word_t val() const { return val_; }
  bool has_error() const { return error_; }
  void set_error() { error_ = true; }

  void set_val(word_t v) { val_ = v; }

private:
  word_t val_ = 0;
  bool error_ = false;
};

#endif // EXPR_SCANNER_HH_
