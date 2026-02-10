import "DPI-C" function void psram_read(input int addr, output int data);
import "DPI-C" function void psram_write(input int addr, input int data);

module psram_cmd(
  input             clock,
  input             valid,
  input       [7:0] cmd,
  input      [31:0] addr,
  input      [31:0] wdata,
  output reg [31:0] rdata
);
  always@(negedge clock) begin
    if (valid)
      if (cmd == 8'heb) begin
        psram_read(addr, rdata);
      end else if (cmd == 8'h38) begin
        psram_write(addr, wdata);
      end else begin
        $fwrite(32'h80000002, "Assertion failed: Unsupport command `%xh`, only support `03h` read command\n", cmd);
        $fatal;
      end
  end
endmodule
