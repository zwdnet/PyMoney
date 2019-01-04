# -*- coding:utf-8 -*-

#一些其它程序需要使用的工具函数
from DataBase import *


#判断输入的日期是否合法
def JudgeDate(time):
    if time < 19800101 or time > 21000101:
        return False
    MonthDay = time % int(time / 10000)
    Month = int(MonthDay/100)
    Day = MonthDay % 100
    if Month == 0 or Month > 12:
        return False
    if Day == 0:
        return False
    BigMonth = [1, 3, 5, 7, 8, 10, 12]
    if Month in BigMonth:
        if Day > 31:
            return False
    elif Month == 2:
        year = time/10000
        if ((year % 4 == 0 and year % 100 != 0) or (year % 100 == 0 and year % 400 == 0)):
            if Day > 29:
                return False
        else:
            if Day > 28:
                return False
    else:
        if Day > 30:
            return False
    return True
    
    
#输出已有的项目类型信息
def ShowType():
    db = DataBase("money.db")
    sql = "select TypeID from IncomeType"
    db.Execute(sql)
    TypeID = db.GetResult()
    for ID in TypeID:
        sql = "select TypeName from IncomeType where TypeID = "
        sql += str(ID[0])
        db.Execute(sql)
        typename = db.GetResult()
        print("%2d...............%s"%(ID[0], typename[0][0]))
        

import decimal
# 将元转化为分，都是整数计算，没有误差
def Yuan2Fen(money):
    decimal.getcontext().prec = 2
    money = 100* decimal.Decimal.from_float(money)
    return money
    
  
# 将分转化为元，用于输出
def Fen2Yuan(money):
    decimal.getcontext().prec = 2
    money = money/decimal.Decimal(100.0)
    return money
    
    
if __name__ == "__main__":
    """
    while True:
        t = int(input("输入八位时间:"))
        print(JudgeDate(t))
        if t == 0:
            break
    """
    ShowType()
    print(Yuan2Fen(3.54))
    print(0.58)
    print(Yuan2Fen(0.58))
    for i in range(100):
        x = 0.01*i
        f = Yuan2Fen(x)
        y = Fen2Yuan(f)
        print(x, f, y)
            
    