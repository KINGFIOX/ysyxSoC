import "DPI-C" function shortint sdram_read(int addr);
import "DPI-C" function void sdram_write(int addr, byte data);

module sdram_cmd(
  input clock,
  input valid,
  input wen,
  input [1:0] dqm_n,
  input [31:0] addr,
  input [15:0] wdata,
  output reg [15:0] rdata
);
  always@(posedge clock) begin
    rdata <= 0;
    if (valid) begin
      if (wen) begin
        if (!dqm_n[0]) begin
          sdram_write(addr, wdata[7:0]);
        end
        if (!dqm_n[1]) begin
          sdram_write(addr + 1, wdata[15:8]);
        end
      end else begin
        rdata <= sdram_read(addr);
      end
    end
  end
endmodule
